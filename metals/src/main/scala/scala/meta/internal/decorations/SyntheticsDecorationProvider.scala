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
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.ServerCommands
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

import org.eclipse.{lsp4j => l}

final class SyntheticsDecorationProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    client: DecorationClient,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    focusedDocument: () => Option[AbsolutePath],
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    trees: Trees,
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider {
  private object Document {
    /* We update it with each compilation in order not read the same file on
     * each change. When typing documents stay the same most of the time.
     */
    private val document = new AtomicReference[TextDocument]

    def currentDocument: Option[TextDocument] = Option(document.get())

    def set(doc: TextDocument): Unit = document.set(doc)
  }

  /**
   * Publish synthetic decorations for path.
   * @param path path of the file to publish synthetic decorations for
   * @param isRefresh we don't want to send anything if all flags are disabled unless
   * it's a refresh, in which case we might want to remove decorations.
   */
  def publishSynthetics(
      path: AbsolutePath,
      isRefresh: Boolean = false,
  ): Future[Unit] = Future {
    if (isRefresh && !areSyntheticsEnabled) publish(path, Nil)
    else if (areSyntheticsEnabled) {
      val decorations = syntheticDecorations(path)
      publish(path, decorations)
    }
  }

  override def onDelete(path: AbsolutePath): Unit = ()
  override def reset(): Unit = ()

  override def onChange(
      textDocument: TextDocuments,
      path: AbsolutePath,
  ): Unit = {
    for {
      focused <- focusedDocument()
      if path == focused || !clientConfig.isDidFocusProvider()
      if areSyntheticsEnabled
      textDoc <- enrichWithText(textDocument.documents.headOption, path)
    } {
      publish(path, decorations(path, textDoc))
    }
  }

  def refresh(): Future[Unit] = {
    focusedDocument() match {
      case Some(doc) => publishSynthetics(doc, isRefresh = true)
      case None => Future.unit
    }
  }

  def addSyntheticsHover(
      params: HoverExtParams,
      pcHover: Option[l.Hover],
  ): Option[l.Hover] =
    if (areSyntheticsEnabled) {
      val path = params.textDocument.getUri().toAbsolutePath
      val position = params.getPosition
      val line = position.getLine()
      val isInlineDecorationProvider = clientConfig.isInlineDecorationProvider()
      val newHover = currentDocument(path) match {
        case Some(textDocument) =>
          val edit = buffer.tokenEditDistance(path, textDocument.text, trees)
          val printer = new SemanticdbTreePrinter(
            isHover = true,
            toHoverString(textDocument, params.textDocument.getUri()),
            PrinterSymtab.fromTextDocument(textDocument),
            clientConfig.icons().rightArrow,
          )
          val syntheticsAtLine = for {
            synthetic <- textDocument.synthetics
            range <- synthetic.range.toIterable
            currentRange <- edit.toRevisedStrict(range).toIterable
            lspRange = currentRange.toLsp
            if range.encloses(position, includeLastCharacter = true) ||
              !isInlineDecorationProvider // in this case we want to always show the full line
            (fullSnippet, range) <- printer
              .printSyntheticInfo(
                textDocument,
                synthetic,
                userConfig(),
                isInlineProvider = clientConfig.isInlineDecorationProvider(),
              )
              .toIterable
            if range.endLine == line
            realRange <- edit.toRevisedStrict(range).toIterable
            lspRealRange = realRange.toLsp
            if lspRealRange.getEnd.getLine == line
          } yield (lspRealRange, fullSnippet)

          if (syntheticsAtLine.size > 0) {
            if (isInlineDecorationProvider) {
              createHoverAtPoint(
                syntheticsAtLine,
                pcHover,
                params.getPosition,
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

  private def syntheticDecorations(
      path: AbsolutePath
  ): Seq[DecorationOptions] = {

    // If focused document is not supported we can't be sure what is currently open
    def isFocusedDocument =
      focusedDocument().contains(path) || !clientConfig.isDidFocusProvider()

    /**
     * Worksheets currently do not use semanticdb, which is why this will not work.
     * If at any time worksheets will support it, we need to make sure that the
     * evaluation will not be replaced with implicit decorations.
     */
    currentDocument(path) match {
      case Some(doc) if isFocusedDocument =>
        decorations(path, doc)
      case _ =>
        Nil
    }
  }

  private def publish(
      path: AbsolutePath,
      decorations: Seq[DecorationOptions],
  ): Unit = {
    val params =
      new PublishDecorationsParams(
        path.toURI.toString(),
        decorations.toArray,
        if (clientConfig.isInlineDecorationProvider()) true else null,
      )

    client.metalsPublishDecorations(params)
  }

  private def areSyntheticsEnabled: Boolean = {
    userConfig().showImplicitArguments || userConfig().showInferredType || userConfig().showImplicitConversionsAndClasses
  }

  private def createHoverAtPoint(
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
      position: l.Position,
  ): Option[l.Hover] = {
    val interestingSynthetics = syntheticsAtLine.collect {
      case (range, text)
          if range.getEnd().getCharacter() == position.getCharacter() =>
        text
    }.distinct

    if (interestingSynthetics.nonEmpty)
      addToHover(
        pcHover,
        "**Synthetics**:\n\n"
          + interestingSynthetics.mkString("\n"),
      )
    else None
  }

  private def createHoverAtLine(
      path: AbsolutePath,
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
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
      lineText: String,
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
        + HoverMarkup(lineWithDecorations.trim()),
    )
  }

  private def addToHover(
      pcHover: Option[l.Hover],
      text: String,
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
            previousContent + "\n" + text,
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
      path: AbsolutePath,
  ): Option[TextDocument] = {
    for {
      doc <- textDocument
      source <- fingerprints.loadLastValid(path, doc.md5, charset)
      docWithText = doc.withText(source)
      _ = Document.set(docWithText)
    } yield docWithText
  }

  private def localSymbolName(symbol: String, textDoc: TextDocument): String = {
    textDoc.symbols
      .find(_.symbol == symbol)
      .map(_.displayName)
      .getOrElse(symbol)
  }

  private def fullyQualifiedName(
      symbol: String,
      textDoc: TextDocument,
  ): String = {
    if (symbol.isLocal) localSymbolName(symbol, textDoc)
    else {
      symbol
        .replace("/", ".")
        .stripSuffix(".")
        .stripSuffix("#")
        .stripPrefix("_empty_.")
        .replace("#", ".")
        .replaceAll(raw"\(\+?\d*\)", "")
    }
  }

  private def toHoverString(textDoc: TextDocument, uri: String)(
      symbol: String
  ): String = {
    val link =
      clientConfig
        .commandInHtmlFormat()
        .flatMap(gotoLink(symbol, textDoc, uri, _))
    link match {
      case Some(link) =>
        val simpleName =
          if (symbol.isLocal) localSymbolName(symbol, textDoc)
          else symbol.desc.name.value
        s"[$simpleName]($link)"
      case _ => fullyQualifiedName(symbol, textDoc)
    }
  }

  private def gotoLink(
      symbol: String,
      textDocument: s.TextDocument,
      uri: String,
      format: CommandHTMLFormat,
  ): Option[String] = {
    if (symbol.isLocal) {
      textDocument.occurrences.collectFirst {
        case s.SymbolOccurrence(Some(range), `symbol`, role)
            if role.isDefinition =>
          gotoLocationUsingUri(uri, range, format)
      }
    } else {
      Some(gotoSymbolUsingUri(symbol, format))
    }
  }

  private def gotoLocationUsingUri(
      uri: String,
      range: s.Range,
      format: CommandHTMLFormat,
  ): String = {
    val location = ClientCommands.WindowLocation(uri, range.toLsp)
    ClientCommands.GotoLocation.toCommandLink(location, format)
  }

  private def gotoSymbolUsingUri(
      symbol: String,
      format: CommandHTMLFormat,
  ): String = {
    ServerCommands.GotoSymbol.toCommandLink(symbol, format)
  }

  private def toDecorationString(
      textDoc: TextDocument
  )(symbol: String): String = {
    if (symbol.isLocal)
      textDoc.symbols
        .find(_.symbol == symbol)
        .map(_.displayName)
        .getOrElse("_")
    else symbol.desc.name.value
  }

  private def decorationOptions(
      lspRange: l.Range,
      decorationText: String,
  ) = {
    // We don't add hover due to https://github.com/microsoft/vscode/issues/105302
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          decorationText,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7,
        )
      ),
    )
  }

  private def decorations(
      path: AbsolutePath,
      textDocument: TextDocument,
  ): Seq[DecorationOptions] = {
    if (clientConfig.isInlineDecorationProvider()) {

      lazy val edit = buffer.tokenEditDistance(path, textDocument.text, trees)

      val decorationPrinter = new SemanticdbTreePrinter(
        isHover = false,
        toDecorationString(textDocument),
        PrinterSymtab.fromTextDocument(textDocument),
        clientConfig.icons().rightArrow,
      )

      val decorations = for {
        synthetic <- textDocument.synthetics
        (decoration, range) <- decorationPrinter
          .printSyntheticInfo(
            textDocument,
            synthetic,
            userConfig(),
          )
          .toIterable
        currentRange <- edit.toRevisedStrict(range).toIterable
        lspRange = currentRange.toLsp
      } yield decorationOptions(lspRange, decoration)

      val typDecorations =
        if (userConfig().showInferredType)
          typeDecorations(path, textDocument, decorationPrinter)
        else Nil
      decorations ++ typDecorations
    } else
      Nil
  }

  private def typeDecorations(
      path: AbsolutePath,
      textDocument: TextDocument,
      decorationPrinter: SemanticdbTreePrinter,
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
    val uri = path.toURI.toString
    val typeDecorations = for {
      tree <- trees.get(path).toIterable
      textDocumentInput = Input.VirtualFile(uri, textDocument.text)
      treeInput = Input.VirtualFile(uri, tree.pos.input.text)
      semanticDbToTreeEdit = TokenEditDistance(
        textDocumentInput,
        treeInput,
        trees,
      )
      treeToBufferEdit = buffer.tokenEditDistance(
        path,
        tree.pos.input.text,
        trees,
      )
      occ <- textDocument.occurrences
      range <- occ.range.toIterable
      treeRange <- semanticDbToTreeEdit.toRevisedStrict(range).toIterable
      if occ.role.isDefinition && allMissingTypeRanges(treeRange)
      signature <- textDocument.symbols.find(_.symbol == occ.symbol).toIterable
      decorationPosition = methodPositions.getOrElse(treeRange, treeRange)
      realPosition <- treeToBufferEdit.toRevisedStrict(decorationPosition)
    } yield {
      val lspRange = realPosition.toLsp
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
