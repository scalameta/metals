package scala.meta.internal.metals.mcp

import scala.meta.io.AbsolutePath

object McpPrinter {
  implicit class XtensionSearchResult(result: SymbolSearchResult) {
    def show: String = s"${result.symbolType.name} ${result.path}"
  }

  implicit class XtensionSymbolInspectResult(result: SymbolInspectResult) {
    def show: String = {
      def showMembers(members: List[String]): String =
        if (members.isEmpty) ""
        else members.mkString("\n\t - ", "\n\t - ", "")
      result match {
        case ObjectInspectResult(_, members) =>
          s"object ${result.name}${showMembers(members)}"
        case ClassInspectResult(_, members, constructors) =>
          s"class ${result.name}${showMembers(constructors)}${showMembers(members)}"
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
}
