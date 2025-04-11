package scala.meta.internal.metals.mcp

import scala.meta.io.AbsolutePath

object McpPrinter {
  implicit class XtensionSearchResult(result: SymbolSearchResult) {
    def show: String = s"${result.symbolType.name} ${result.path}"
  }

  implicit class XtensionSymbolInspectResult(result: SymbolInspectResult) {
    def show(fullPath: Boolean = true): String = {
      def showMembers(members: List[SymbolInspectResult]): String =
        if (members.isEmpty) ""
        else
          members.ssorted
            .map(_.show(fullPath = false))
            .mkString("\n\t - ", "\n\t - ", "")
      def name = if (fullPath) result.path else result.name
      result match {
        case ObjectInspectResult(_, members) =>
          s"object $name${showMembers(members)}"
        case ClassInspectResult(_, members, constructors) =>
          s"class $name${showMembers(constructors)}${showMembers(members)}"
        case TraitInspectResult(_, members) =>
          s"trait $name${showMembers(members)}"
        case MethodInspectResult(_, returnType, parameters, visibility, kind) =>
          val kindString = kind match {
            case SymbolType.Constructor => "constructor"
            case SymbolType.Function => "function"
            case _ => "method"
          }
          val visibilityString =
            if (visibility.nonEmpty) s"$visibility " else ""
          s"$visibilityString$kindString $name${parameters.collect {
              case TermParamList(params, "") => s"(${params.mkString(", ")})"
              case TermParamList(params, prefix) => s"($prefix${params.mkString(", ")})"
              case TypedParamList(params) if params.nonEmpty => s"[${params.mkString(", ")}]"
            }.mkString}${if (kind == SymbolType.Constructor) "" else s": $returnType"}"
        case ShortMethodInspectResult(_, kind) =>
          val kindString = kind match {
            case SymbolType.Constructor => "constructor"
            case SymbolType.Function => "function"
            case _ => "method"
          }
          s"$kindString $name"
        case PackageInspectResult(_, members) =>
          s"package $name${showMembers(members)}"
        case _ => s"${result.symbolType.name} $name"
      }
    }
  }

  implicit class XtensionSearchResultSeq(result: Seq[SymbolSearchResult]) {
    def show: String = result.map(_.show).sorted.mkString("\n")
  }

  implicit class XtensionSymbolInspectResultList(
      result: List[SymbolInspectResult]
  ) {
    def ssorted: List[SymbolInspectResult] =
      result.sortBy(x => (x.symbolType.toString(), x.name))
    def show: String = ssorted.map(_.show()).mkString("\n")
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

  implicit class XtensionSymbolUsageList(result: List[SymbolUsage]) {
    def show(projectRoot: AbsolutePath): String =
      result.map(_.show(projectRoot)).sorted.mkString("\n")
  }
}
