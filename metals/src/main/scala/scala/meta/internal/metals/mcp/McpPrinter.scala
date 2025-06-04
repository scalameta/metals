package scala.meta.internal.metals.mcp

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
          case (path, diag) if diag.getSeverity() == DiagnosticSeverity.Error =>
            s"${path.toRelative(projectRoot)} ${showDiagnostic(diag)}"
        }
        .mkString("\n")
  }

  implicit class XtensionDiagnostics(
      diagnostics: Seq[Diagnostic]
  ) {
    def show(): String =
      diagnostics
        .collect {
          case diag if diag.getSeverity() == DiagnosticSeverity.Error =>
            showDiagnostic(diag)
        }
        .mkString("\n")
  }

  private def showDiagnostic(diagnostic: Diagnostic): String = {
    val startLine = diagnostic.getRange().getStart().getLine()
    val endLine = diagnostic.getRange().getEnd().getLine()
    s"L$startLine-L$endLine:\n${diagnostic.getMessage()}"
  }
}
