package scala.meta.internal.pc.completions

/**
 * @param suffixes which we should insert
 * @param snippet which suffix should we insert the snippet $0
 */
case class CompletionSuffix(
    suffixes: Set[SuffixKind],
    snippet: SuffixKind,
):
  def addLabelSnippet: Boolean = suffixes.contains(SuffixKind.Bracket)
  def hasSnippet: Boolean = snippet != SuffixKind.NoSuffix
  def chain(copyFn: CompletionSuffix => CompletionSuffix): CompletionSuffix = copyFn(this)
  def withNewSuffix(kind: SuffixKind): CompletionSuffix =
    CompletionSuffix(suffixes + kind, snippet)
  def withNewSuffixSnippet(kind: SuffixKind): CompletionSuffix =
    CompletionSuffix(suffixes + kind, kind)
  def toEdit: String =
    def loop(suffixes: List[SuffixKind]): String =
      def cursor = if suffixes.head == snippet then "$0" else ""
      suffixes match
        case SuffixKind.Brace :: tail => s"($cursor)" + loop(tail)
        case SuffixKind.Bracket :: tail => s"[$cursor]" + loop(tail)
        case SuffixKind.Template :: tail => s" {$cursor}" + loop(tail)
        case _ => ""
    loop(suffixes.toList)
  def toEditOpt: Option[String] =
    val edit = toEdit
    if edit.nonEmpty then Some(edit) else None
end CompletionSuffix

object CompletionSuffix:
  val empty: CompletionSuffix = CompletionSuffix(
    suffixes = Set.empty,
    snippet = SuffixKind.NoSuffix,
  )

enum SuffixKind:
  case Brace, Bracket, Template, NoSuffix
