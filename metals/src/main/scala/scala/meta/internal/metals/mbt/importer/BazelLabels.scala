package scala.meta.internal.metals.mbt.importer

import scala.meta.internal.metals.mbt.MbtGlobMatcher

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

  /**
   * A `glob()` (or a label whose name is itself a glob) from a rule `srcs`
   * attribute.
   *
   * Patterns inside `glob([...])` are relative to the Bazel package.
   * A file label that contains glob metacharacters is already workspace-relative.
   */
  case class SrcGlob(
      includes: List[String],
      excludes: List[String],
      packageRelative: Boolean,
  )

  def srcGlob(src: String): Option[SrcGlob] = {
    val trimmed = src.trim
    parseGlobCall(trimmed).orElse(globLabel(trimmed))
  }

  private val quotedPattern = """"([^"]*)"|'([^']*)'""".r

  private def parseGlobCall(text: String): Option[SrcGlob] = {
    val open = text.indexOf('(')
    if (open < 0 || !text.endsWith(")")) None
    else {
      val prefix = text.substring(0, open).trim
      if (prefix != "glob" && prefix != "native.glob") None
      else {
        val args = text.substring(open + 1, text.length - 1)
        val excludeAt = args.indexOf("exclude")
        val includes = List.newBuilder[String]
        val excludes = List.newBuilder[String]
        for (m <- quotedPattern.findAllMatchIn(args)) {
          val value = Option(m.group(1)).getOrElse(m.group(2))
          if (value.nonEmpty) {
            if (excludeAt >= 0 && m.start >= excludeAt) excludes += value
            else includes += value
          }
        }
        val included = includes.result()
        if (included.isEmpty) None
        else
          Some(
            SrcGlob(
              includes = included,
              excludes = excludes.result(),
              packageRelative = true,
            )
          )
      }
    }
  }

  private def globLabel(text: String): Option[SrcGlob] = {
    val path = fileLabelToWorkspaceRelativePath(text).getOrElse(text)
    if (MbtGlobMatcher.isGlob(path))
      Some(
        SrcGlob(
          includes = List(path),
          excludes = Nil,
          packageRelative = false,
        )
      )
    else None
  }

  /** Resolve a glob pattern to a workspace-relative Java glob. */
  def workspaceGlob(
      packageDir: String,
      pattern: String,
      packageRelative: Boolean,
  ): String = {
    val normalized = pattern.trim.replace('\\', '/').stripPrefix("./")
    fileLabelToWorkspaceRelativePath(normalized).getOrElse {
      if (!packageRelative || packageDir.isEmpty) normalized.stripPrefix("/")
      else if (normalized.startsWith(s"$packageDir/")) normalized
      else s"$packageDir/$normalized"
    }
  }

}
