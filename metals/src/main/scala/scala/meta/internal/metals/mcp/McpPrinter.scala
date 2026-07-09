package scala.meta.internal.metals.mcp

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

object McpPrinter {
  implicit class XtensionSearchResult(result: SymbolSearchResult) {
    def show: String = s"${result.symbolType.name} ${result.path}"
  }

  implicit class XtensionSymbolInspectResult(result: SymbolInspectResult) {
    def show: String = {
      def showMembers(members: List[String]): String =
        if (members.isEmpty) ""
        else members.mkString("\n\t - ", "\n\t - ", "")
      def showAuxContext(auxilaryContext: String): String =
        if (auxilaryContext.isEmpty) ""
        else auxilaryContext.split('\n').map("\n\t" + _).mkString
      result match {
        case ObjectInspectResult(_, members, auxilaryContext) =>
          s"object ${result.name}${showMembers(members)}${showAuxContext(auxilaryContext)}"
        case ClassInspectResult(_, members, constructors, auxilaryContext) =>
          s"class ${result.name}${showMembers(constructors)}${showMembers(members)}${showAuxContext(auxilaryContext)}"
        case TraitInspectResult(_, members) =>
          s"trait ${result.name}${showMembers(members)}"
        case MethodInspectResult(_, signatures, kind) =>
          val kindString = kind match {
            case SymbolType.Constructor => "constructor"
            case SymbolType.Function => "function"
            case _ => "method"
          }
          signatures.map(s => s"$kindString $s").mkString("\n")
        case PackageInspectResult(_, members) =>
          s"package ${result.path}${showMembers(members)}"
        case _ => s"${result.symbolType.name} ${result.name}"
      }
    }
  }

  implicit class XtensionSearchResultSeq(result: Seq[SymbolSearchResult]) {
    def show: String = result.map(_.show).sorted.mkString("\n")
  }

  implicit class XtensionSymbolDocumentation(
      result: SymbolDocumentationSearchResult
  ) {
    def show: String =
      result.documentation.getOrElse("Found symbol but no documentation")
  }

  implicit class XtensionSymbolUsage(result: SymbolUsage) {
    def show(projectRoot: AbsolutePath): String =
      s"${result.path.toRelative(projectRoot)}:${result.line}"
  }

  implicit class XtensionImplicitsResult(result: ImplicitsResult) {
    def show: String = result match {
      case ImplicitsResult.NoFile(requested) =>
        val what = requested.getOrElse("<no file provided>")
        s"Error: could not resolve file: $what"
      case ImplicitsResult.Found(file, arguments, conversions) =>
        if (arguments.isEmpty && conversions.isEmpty)
          s"No implicits found in $file"
        else
          List(
            "Implicit arguments:" -> arguments,
            "Implicit conversions:" -> conversions,
          ).collect {
            case (header, hints) if hints.nonEmpty =>
              (header :: hints.map(showImplicitHint(file, _))).mkString("\n")
          }.mkString("\n")
    }
  }

  /**
   * Renders a single implicit hint line. Positions are 1-based `line:col`
   * (human/editor convention), converted from the 0-based positions the
   * presentation compiler returns. `->` targets are semanticdb symbols for
   * out-of-file implicits or `(line:col)` (1-based) for in-file definitions;
   * the `->` is omitted entirely when there are no targets.
   */
  private def showImplicitHint(file: String, hint: ImplicitHint): String = {
    val line = hint.position.getLine() + 1
    val col = hint.position.getCharacter() + 1
    val targets = hint.targets.map {
      case Left(symbol) => symbol
      case Right(pos) => s"(${pos.getLine() + 1}:${pos.getCharacter() + 1})"
    }
    val arrow = if (targets.isEmpty) "" else s"  ->  ${targets.mkString(", ")}"
    s"  $file:$line:$col  ${hint.label}$arrow"
  }

  implicit class XtensionSymbolInspectResultList(
      result: List[SymbolInspectResult]
  ) {
    def ssorted: List[SymbolInspectResult] =
      result.sortBy(x => (x.symbolType.toString(), x.name))
    def show: String = ssorted.map(_.show).mkString("\n")
  }

  implicit class XtensionSymbolUsageList(result: List[SymbolUsage]) {
    def show(projectRoot: AbsolutePath): String =
      result.map(_.show(projectRoot)).sorted.mkString("\n")
  }

  implicit class XtensionDiagnosticsWithPath(
      diagnostics: Seq[(AbsolutePath, Diagnostic)]
  ) {
    def show(projectRoot: AbsolutePath): String =
      diagnostics
        .collect {
          case (path, diag)
              if diag.getSeverity() == DiagnosticSeverity.Error ||
                diag.getSeverity() == DiagnosticSeverity.Warning =>
            s"${path.toRelative(projectRoot)} ${showDiagnostic(diag)}"
        }
        .mkString("\n")

    def hasErrors: Boolean = diagnostics.exists { case (_, diag) =>
      diag.getSeverity() == DiagnosticSeverity.Error
    }

    def hasWarnings: Boolean = diagnostics.exists { case (_, diag) =>
      diag.getSeverity() == DiagnosticSeverity.Warning
    }
  }

  implicit class XtensionDiagnostics(
      diagnostics: Seq[Diagnostic]
  ) {
    def show(): String =
      diagnostics
        .collect {
          case diag
              if diag.getSeverity() == DiagnosticSeverity.Error ||
                diag.getSeverity() == DiagnosticSeverity.Warning =>
            showDiagnostic(diag)
        }
        .mkString("\n")

    def hasErrors: Boolean = diagnostics.exists(
      _.getSeverity() == DiagnosticSeverity.Error
    )

    def hasWarnings: Boolean = diagnostics.exists(
      _.getSeverity() == DiagnosticSeverity.Warning
    )
  }

  private def showDiagnostic(diagnostic: Diagnostic): String = {
    val startLine = diagnostic.getRange().getStart().getLine()
    val startColumn = diagnostic.getRange().getStart().getCharacter()
    val endLine = diagnostic.getRange().getEnd().getLine()
    val endColumn = diagnostic.getRange().getEnd().getCharacter()
    val severityText = diagnostic.getSeverity() match {
      case DiagnosticSeverity.Error => "Error"
      case DiagnosticSeverity.Warning => "Warning"
      case DiagnosticSeverity.Information => "Info"
      case DiagnosticSeverity.Hint => "Hint"
      case _ => "Unknown"
    }
    s"L$startLine:C$startColumn-L$endLine:C$endColumn: [$severityText]\n${diagnostic.getMessageAsString}"
  }
}
