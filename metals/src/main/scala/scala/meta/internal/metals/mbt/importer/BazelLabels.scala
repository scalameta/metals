package scala.meta.internal.metals.mbt.importer

object BazelLabels {

  /** Split an in-repo label `//pkg/path:name` into (`pkg/path`, `name`). */
  def splitLabel(label: String): Option[(String, String)] = {
    val s = label.trim
    if (s.startsWith("//")) {
      val rest = s.substring(2)
      val colon = rest.lastIndexOf(':')
      if (colon < 0) None
      else Some((rest.substring(0, colon), rest.substring(colon + 1)))
    } else None
  }

  def packageKey(ruleLabel: String): Option[String] =
    splitLabel(ruleLabel).map { case (pkg, _) => s"//$pkg" }

  /**
   * Map a Bazel file label `//path/to:File.ext` to a workspace-relative path
   * `path/to/File.ext`.
   */
  def fileLabelToWorkspaceRelativePath(fileLabel: String): Option[String] =
    splitLabel(fileLabel).collect {
      case (pkg, name) if name.nonEmpty =>
        if (pkg.isEmpty) name else s"$pkg/$name"
    }

}
