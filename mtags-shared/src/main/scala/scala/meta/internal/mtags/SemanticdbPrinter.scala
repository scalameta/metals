package scala.meta.internal.mtags

import scala.collection.BufferedIterator

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbRanges._

object SemanticdbPrinter {
  def printDocument(
      doc: Semanticdb.TextDocument,
      includeInfo: Boolean = false
  ): String = {
    val out = new StringBuilder

    val symtab = doc.getSymbolsList().asScala.map(s => s.getSymbol() -> s).toMap

    def printRange(
        range: Semanticdb.Range,
        message: String,
        line: String
    ): Unit = {
      out.append("// ")
      val start = out.length
      val indent = " ".repeat(range.getStartCharacter())
      out.append(indent)
      val isSingleLine =
        range.getStartLine() == range.getEndLine()
      if (isSingleLine) {
        val length =
          math.max(1, range.getEndCharacter() - range.getStartCharacter())
        out.append("^".repeat(length))
      } else {
        val length = math.max(1, line.stripLineEnd.length() - indent.length())
        out.append("^".repeat(length))
        out.append("!")
        out.append(range.getEndLine())
        out.append(":")
        out.append(range.getEndCharacter())
      }
      out.append(" ")
      val caretCharacter = out.length - start
      message.trim().linesIterator.toList match {
        case messageLine :: rest =>
          out.append(messageLine)
          rest.foreach { messageLine =>
            out.append("\n")
            out.append("// ")
            out.append(" ".repeat(caretCharacter))
            out.append(messageLine)
          }
        case Nil =>
          out.append("<empty message>")
      }
      out.append("\n")
    }

    def isInvalidRange(range: Semanticdb.Range): Boolean = {
      range.getStartLine() < 0 ||
      range.getStartCharacter() < 0 ||
      range.getEndLine() < 0 ||
      range.getEndCharacter() < 0
    }

    val occs = doc
      .getOccurrencesList()
      .asScala
      .sortBy(_.getRange())
      .iterator
      .filterNot(occ => isInvalidRange(occ.getRange()))
      .buffered
    val diags = doc
      .getDiagnosticsList()
      .asScala
      .sortBy(_.getRange())
      .iterator
      .filterNot(diag => isInvalidRange(diag.getRange()))
      .buffered
    val lines = doc.getText().linesWithSeparators
    lines.zipWithIndex.foreach { case (line, lineNumber) =>
      if (line.trim().nonEmpty) {
        out.append(
          // Indentation for occurrence comments
          " ".repeat("// ".length())
        )
      }
      out.append(replaceTabs(line))

      def foreach[T](
          it: BufferedIterator[T],
          getRange: T => Semanticdb.Range
      )(
          f: T => Unit
      ): Unit = {
        while (it.hasNext && getRange(it.head).getStartLine() == lineNumber) {
          f(it.next())
        }
      }
      foreach[Semanticdb.SymbolOccurrence](occs, _.getRange()) { occ =>
        val infoMessage =
          if (
            !includeInfo ||
            occ.getRole() != Semanticdb.SymbolOccurrence.Role.DEFINITION
          ) {
            ""
          } else {
            val occSymbol = occ.getSymbol()
            symtab.get(occSymbol) match {
              case Some(info) =>
                val props = properties(info.getProperties())
                s" ${info.getKind()}$props"
              case None => ""
            }
          }
        val message =
          s"${occ.getRole().toString().toLowerCase()} ${occ.getSymbol()}$infoMessage"
        printRange(occ.getRange(), message, line)
      }
      foreach[Semanticdb.Diagnostic](diags, _.getRange()) { diag =>
        val message =
          s"diagnostic - ${diag.getSeverity().toString().toLowerCase()} ${diag.getMessage()}"
        printRange(diag.getRange(), message, line)
      }
    }
    out.toString()
  }

  private def properties(properties: Int): String = {
    val props = for {
      prop <- Semanticdb.SymbolInformation.Property.values()
      if prop != Semanticdb.SymbolInformation.Property.UNKNOWN_PROPERTY
      if prop != Semanticdb.SymbolInformation.Property.UNRECOGNIZED
      if (properties & prop.getNumber()) != 0
    } yield prop.toString().toLowerCase()
    if (props.isEmpty) ""
    else props.mkString("(", ", ", ")")
  }

  private def replaceTabs(line: String): String = {
    val pattern = "^(\t+)"
    val tabCount = pattern.r.findFirstIn(line).fold(0)(_.length)
    line.replaceFirst(pattern, " ".repeat(tabCount))
  }
}
