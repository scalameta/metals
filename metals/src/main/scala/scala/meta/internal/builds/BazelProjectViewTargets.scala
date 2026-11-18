package scala.meta.internal.builds

import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.io.AbsolutePath

/**
 * Reads `targets:` entries from a Bazel BSP / IntelliJ-style project view
 * (`.bazelproject` or `*.bazelproject`), used to scope `bazel query` to the same
 * targets the user configured for Bazel BSP.
 */
object BazelProjectViewTargets {

  /** Default when no project view or no `targets:` section is present. */
  val defaultPatterns: List[String] = List("//...")

  def patterns(projectRoot: AbsolutePath): List[String] =
    BazelBuildTool.existingProjectView(projectRoot) match {
      case None => defaultPatterns
      case Some(file) =>
        val parsed = parseFromContent(file.readText)
        if (parsed.isEmpty) defaultPatterns else parsed
    }

  /**
   * Parses target patterns listed under `targets:` (YAML-style indentation).
   * Inline `targets: //foo/...` is supported. Stops at the next top-level key.
   */
  def parseFromContent(content: String): List[String] = {
    val lines = content.linesIterator.toList
    val targetsIdx = lines.indexWhere(_.trim.startsWith("targets:"))
    if (targetsIdx < 0) {
      Nil
    } else {
      val buf = List.newBuilder[String]
      val header = lines(targetsIdx).trim
      val afterColon = header.indexOf(':')
      if (afterColon >= 0) {
        val inline = header.substring(afterColon + 1).trim
        if (inline.nonEmpty && !inline.startsWith("#")) {
          buf += inline
        }
      }
      val rest = lines.drop(targetsIdx + 1)
      val (continuation, _) = rest.span { line =>
        line.isEmpty ||
        line.forall(c => c == ' ' || c == '\t') ||
        Character.isWhitespace(line.charAt(0))
      }
      for (line <- continuation) {
        if (!line.forall(c => c == ' ' || c == '\t')) {
          val p = line.trim
          if (p.nonEmpty && !p.startsWith("#")) {
            buf += p
          }
        }
      }
      buf.result()
    }
  }

}
