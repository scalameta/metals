package scala.meta.internal.decorations

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.{meta => m}

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metap.PrinterSymtab
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.HoverMarkup
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token.RightParen
import scala.meta.tokens.{Token => T}

import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.{lsp4j => l}

final class SyntheticsDecorationProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    client: DecorationClient,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    diagnostics: Diagnostics,
    focusedDocument: () => Option[AbsolutePath],
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    trees: Trees
)(implicit ec: ExecutionContext) {

  private object Document {
    /* We update it with each compilation in order not read the same file on
     * each change. When typing documents stay the same most of the time.
     */
    private val document = new AtomicReference[TextDocument]

    def currentDocument: Option[TextDocument] = Option(document.get())

    def set(doc: TextDocument): Unit = document.set(doc)
  }

  def publishSynthetics(path: AbsolutePath): Future[Unit] = {

    // If focused document is not supported we can't be sure what is currently open
    def isFocusedDocument =
      focusedDocument().contains(path) || !clientConfig.isDidFocusProvider()

    /**
     * Worksheets currently do not use semanticdb, which is why this will not work.
     * If at any time worksheets will support it, we need to make sure that the
     * evaluation will not be replaced with implicit decorations.
     */
    if (!path.isWorksheet && isFocusedDocument) {
      val textDocument = currentDocument(path)
      textDocument match {
        case Some(doc) =>
          Future(publishSyntheticDecorations(path, doc))
        case _ =>
          Future.successful(())
      }

    } else
      Future.successful(())
  }

  def onChange(textDocument: TextDocuments, path: AbsolutePath): Unit = {
    for {
      focused <- focusedDocument()
      if path == focused || !clientConfig.isDidFocusProvider()
      textDoc <- enrichWithText(textDocument.documents.headOption, path)
    } {
      publishSyntheticDecorations(path, textDoc)
    }
  }

  def refresh(): Unit = {
    focusedDocument().foreach(publishSynthetics)
  }

  def addSyntheticsHover(
      params: TextDocumentPositionParams,
      pcHover: Option[l.Hover]
  ): Option[l.Hover] =
    if (isSyntheticsEnabled) {
      val path = params.getTextDocument().getUri().toAbsolutePath
      val line = params.getPosition().getLine()
      val newHover = currentDocument(path) match {
        case Some(textDocument) =>
          val edit = buffer.tokenEditDistance(path, textDocument.text, trees)
          val printer = new SemanticdbTreePrinter(
            isHover = true,
            toHoverString(textDocument),
            PrinterSymtab.fromTextDocument(textDocument),
            clientConfig.icons().rightArrow,
            clientConfig.icons().ellipsis
          )
          val syntheticsAtLine = for {
            synthetic <- textDocument.synthetics
            range <- synthetic.range.toIterable
            currentRange <- edit.toRevisedStrict(range).toIterable
            lspRange = currentRange.toLSP
            if lspRange.getEnd.getLine == line

            (fullSnippet, range) <- printer
              .printSyntheticInfo(
                textDocument,
                synthetic,
                userConfig(),
                isInlineProvider = clientConfig.isInlineDecorationProvider()
              )
              .toIterable
            realRange <- edit.toRevisedStrict(range).toIterable
            lspRealRange = realRange.toLSP
            if lspRealRange.getEnd.getLine == line
          } yield (lspRealRange, fullSnippet)

          if (syntheticsAtLine.size > 0) {
            if (clientConfig.isInlineDecorationProvider()) {
              createHoverAtPoint(
                path,
                syntheticsAtLine,
                pcHover,
                params.getPosition()
              )
            } else {
              createHoverAtLine(path, syntheticsAtLine, pcHover).orElse(
                pcHover
              )
            }
          } else {
            None
          }
        case None => None
      }
      newHover.orElse(pcHover)
    } else {
      pcHover
    }

  private def isSyntheticsEnabled: Boolean = {
    userConfig().showImplicitArguments || userConfig().showInferredType || userConfig().showImplicitConversionsAndClasses
  }

  private def createHoverAtPoint(
      path: AbsolutePath,
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
      position: l.Position
  ): Option[l.Hover] = {
    val interestingSynthetics = syntheticsAtLine.collect {
      case (range, text)
          if range.getEnd().getCharacter() == position.getCharacter() =>
        text
    }.distinct

    if (interestingSynthetics.nonEmpty)
      addToHover(
        pcHover,
        "**Synthetics**:\n"
          +
            HoverMarkup(
              interestingSynthetics.mkString("\n")
            )
      )
    else None
  }

  private def createHoverAtLine(
      path: AbsolutePath,
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover]
  ): Option[l.Hover] =
    Try {
      val line = syntheticsAtLine.head._1.getEnd().getLine()
      for {
        currentText <- buffer.get(path)
        lineText <- currentText.linesIterator.drop(line).headOption
        created <- createLine(syntheticsAtLine, pcHover, lineText)
      } yield created
    }.toOption.flatten

  private def createLine(
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
      lineText: String
  ) = {
    val withEnd = syntheticsAtLine
      .map { case (range, str) =>
        (range.getEnd().getCharacter(), str)
      }
      .sortBy(_._1) :+ (lineText.size, "")
    val lineWithDecorations = withEnd
      .foldLeft(("", 0)) { case ((current, index), (char, text)) =>
        val toAdd = lineText.substring(index, char) + text
        (current + toAdd, char)
      }
      ._1

    addToHover(
      pcHover,
      "**With synthetics added**:\n"
        + HoverMarkup(
          lineWithDecorations.trim()
        )
    )
  }

  private def addToHover(
      pcHover: Option[l.Hover],
      text: String
  ): Option[l.Hover] = {
    // Left is not handled currently, but we do not use it in Metals
    if (pcHover.exists(_.getContents().isLeft())) {
      None
    } else {
      val previousContent = pcHover
        .filter(_.getContents().isRight())
        .map(_.getContents().getRight.getValue + "\n")
        .getOrElse("")
      Some(
        new l.Hover(
          new l.MarkupContent(
            l.MarkupKind.MARKDOWN,
            previousContent + "\n" + text
          )
        )
      )
    }
  }

  private def currentDocument(path: AbsolutePath): Option[TextDocument] = {
    Document.currentDocument match {
      case Some(doc) if workspace.resolve(doc.uri) == path => Some(doc)
      case _ =>
        val textDocument = semanticdbs
          .textDocument(path)
          .documentIncludingStale
        enrichWithText(textDocument, path)
    }
  }

  private def enrichWithText(
      textDocument: Option[s.TextDocument],
      path: AbsolutePath
  ): Option[TextDocument] = {
    for {
      doc <- textDocument
      source <- fingerprints.loadLastValid(path, doc.md5, charset)
      docWithText = doc.withText(source)
      _ = Document.set(docWithText)
    } yield docWithText
  }

  private def toSymbolName(symbol: String, textDoc: TextDocument): String = {
    if (symbol.isLocal)
      textDoc.symbols
        .find(_.symbol == symbol)
        .map(_.displayName)
        .getOrElse(symbol)
    else symbol
  }

  private def toHoverString(textDoc: TextDocument)(symbol: String): String = {
    toSymbolName(symbol, textDoc)
      .replace("/", ".")
      .stripSuffix(".")
      .stripSuffix("#")
      .stripPrefix("_empty_.")
      .replace("#", ".")
      .replace("()", "")
  }

  private def toDecorationString(
      textDoc: TextDocument
  )(symbol: String): String = {
    if (symbol.isLocal)
      textDoc.symbols
        .find(_.symbol == symbol)
        .map(_.displayName)
        .getOrElse(symbol)
    else symbol.desc.name.value
  }

  private def decorationOptions(
      lspRange: l.Range,
      decorationText: String
  ) = {
    // We don't add hover due to https://github.com/microsoft/vscode/issues/105302
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          decorationText,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7
        )
      )
    )
  }

  private def publishSyntheticDecorations(
      path: AbsolutePath,
      textDocument: TextDocument
  ): Unit = {
    if (clientConfig.isInlineDecorationProvider()) {

      lazy val edit = buffer.tokenEditDistance(path, textDocument.text, trees)

      val decorationPrinter = new SemanticdbTreePrinter(
        isHover = false,
        toDecorationString(textDocument),
        PrinterSymtab.fromTextDocument(textDocument),
        clientConfig.icons().rightArrow,
        clientConfig.icons().ellipsis
      )

      val decorations = for {
        synthetic <- textDocument.synthetics
        (decoration, range) <- decorationPrinter
          .printSyntheticInfo(
            textDocument,
            synthetic,
            userConfig()
          )
          .toIterable
        currentRange <- edit.toRevisedStrict(range).toIterable
        lspRange = currentRange.toLSP
      } yield decorationOptions(lspRange, decoration)

      val typDecorations =
        if (userConfig().showInferredType)
          typeDecorations(path, textDocument, decorationPrinter)
        else Nil
      val params =
        new PublishDecorationsParams(
          path.toURI.toString(),
          (decorations ++ typDecorations).toArray
        )

      client.metalsPublishDecorations(params)
    }
  }

  private def typeDecorations(
      path: AbsolutePath,
      textDocument: TextDocument,
      decorationPrinter: SemanticdbTreePrinter
  ) = {

    val methodPositions = mutable.Map.empty[s.Range, s.Range]

    def explorePatterns(pats: List[m.Pat]): List[s.Range] = {
      pats.flatMap {
        case m.Pat.Var(nm @ m.Term.Name(_)) =>
          List(nm.pos.toSemanticdb)
        case m.Pat.Extract((_, pats)) =>
          explorePatterns(pats)
        case m.Pat.ExtractInfix(lhs, _, pats) =>
          explorePatterns(lhs :: pats)
        case m.Pat.Tuple(tuplePats) =>
          explorePatterns(tuplePats)
        case m.Pat.Bind(_, rhs) =>
          explorePatterns(List(rhs))
        case _ => Nil
      }
    }

    def visit(tree: m.Tree): List[s.Range] = {
      tree match {
        case enumerator: m.Enumerator.Generator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.CaseGenerator =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case enumerator: m.Enumerator.Val =>
          explorePatterns(List(enumerator.pat)) ++ visit(enumerator.rhs)
        case param: m.Term.Param =>
          if (param.decltpe.isEmpty) List(param.name.pos.toSemanticdb) else Nil
        case cs: m.Case =>
          explorePatterns(List(cs.pat)) ++ visit(cs.body)
        case vl: m.Defn.Val =>
          val values =
            if (vl.decltpe.isEmpty) explorePatterns(vl.pats) else Nil
          values ++ visit(vl.rhs)
        case vr: m.Defn.Var =>
          val values =
            if (vr.decltpe.isEmpty) explorePatterns(vr.pats) else Nil
          values ++ vr.rhs.toList.flatMap(visit)
        case df: m.Defn.Def =>
          val namePos = df.name.pos.toSemanticdb

          def lastParamPos = for {
            group <- df.paramss.lastOption
            param <- group.lastOption
            token <- param.findFirstTrailing(_.is[T.RightParen])
          } yield token.pos.toSemanticdb

          def lastTypeParamPos = for {
            typ <- df.tparams.lastOption
            token <- typ.findFirstTrailing(_.is[T.RightBracket])
          } yield token.pos.toSemanticdb

          def lastParen = if (df.paramss.nonEmpty)
            df.name.findFirstTrailing(_.is[RightParen]).map(_.pos.toSemanticdb)
          else None

          val values =
            if (df.decltpe.isEmpty) {
              val destination =
                lastParamPos
                  .orElse(lastParen)
                  .orElse(lastTypeParamPos)
                  .getOrElse(namePos)
              methodPositions += namePos -> destination
              List(namePos)
            } else {
              Nil
            }
          values ++ visit(df.body)
        case other =>
          other.children.flatMap(visit)
      }
    }

    val declarationsWithoutTypes = for {
      tree <- trees.get(path).toIterable
      pos <- visit(tree)
    } yield pos

    val allMissingTypeRanges = declarationsWithoutTypes.toSet
    val typeDecorations = for {
      tree <- trees.get(path).toIterable
      textDocumentInput = Input.VirtualFile(textDocument.uri, textDocument.text)
      treeInput = Input.VirtualFile(textDocument.uri, tree.pos.input.text)
      semanticDbToTreeEdit = TokenEditDistance(
        textDocumentInput,
        treeInput,
        trees
      )
      treeToBufferEdit = buffer.tokenEditDistance(
        path,
        tree.pos.input.text,
        trees
      )
      occ <- textDocument.occurrences
      range <- occ.range.toIterable
      treeRange <- semanticDbToTreeEdit.toRevisedStrict(range).toIterable
      if occ.role.isDefinition && allMissingTypeRanges(treeRange)
      signature <- textDocument.symbols.find(_.symbol == occ.symbol).toIterable
      decorationPosition = methodPositions.getOrElse(treeRange, treeRange)
      realPosition <- treeToBufferEdit.toRevisedStrict(decorationPosition)
    } yield {
      val lspRange = realPosition.toLSP
      signature.signature match {
        case m: MethodSignature =>
          val decoration = ": " + decorationPrinter.printType(m.returnType)
          Some(decorationOptions(lspRange, decoration))
        case m: ValueSignature =>
          val decoration = ": " + decorationPrinter.printType(m.tpe)
          Some(decorationOptions(lspRange, decoration))
        case _ =>
          None
      }
    }
    typeDecorations.flatten
  }
}
