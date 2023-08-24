package scala.meta.internal.decorations

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metap.PrinterSymtab
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.HoverMarkup
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final class SyntheticHoverProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    trees: Trees,
) {
  private object Document {
    /* We update it with each compilation in order not read the same file on
     * each change. When typing documents stay the same most of the time.
     */
    private val document = new AtomicReference[TextDocument]

    def currentDocument: Option[TextDocument] = Option(document.get())

    def set(doc: TextDocument): Unit = document.set(doc)
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

  private def areSyntheticsEnabled: Boolean = {
    val showInferredType = !userConfig().showInferredType.contains(
      "false"
    ) && userConfig().showInferredType.nonEmpty
    userConfig().showImplicitArguments || showInferredType || userConfig().showImplicitConversionsAndClasses
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

}
