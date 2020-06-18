package scala.meta.internal.pantsbuild

import java.nio.file.Path

import scala.meta.io.AbsolutePath

import bloop.config.{Config => C}
import ujson.Arr
import ujson.Obj
import ujson.Str
import ujson.Value

case class PantsGlobs(
    include: List[String],
    exclude: List[String]
) {
  def isEmpty: Boolean = include.isEmpty
  def bloopConfig(workspace: Path, baseDirectory: Path): C.SourcesGlobs = {
    val prefix = AbsolutePath(baseDirectory)
      .toRelative(AbsolutePath(workspace))
      .toURI(true)
      .toString()
    def relativizeGlob(glob: String): String = {
      val pattern = glob
        .stripPrefix(prefix)
        // NOTE(olafur) Pants globs interpret "**/*.scala" as "zero or more
        // directories" while Bloop uses `java.nio.file.PathMatcher`, which
        // interprets it as "one or more directories".
        .replace("**/*", "**")
      s"glob:$pattern"
    }
    val includeGlobs = include.map(relativizeGlob)
    val excludeGlobs = exclude.map(relativizeGlob)
    val walkDepth = PantsGlobs.walkDepth(includeGlobs)
    C.SourcesGlobs(
      baseDirectory,
      walkDepth = walkDepth,
      includes = includeGlobs,
      excludes = excludeGlobs
    )
  }

  private def isRecursiveGlob(glob: String) =
    glob.endsWith("**/*.scala") ||
      glob.endsWith("**/*.java")
  private def stripRecursiveGlob(glob: String) =
    glob
      .stripSuffix("**/*.scala")
      .stripSuffix("**/*.java")

  def isStatic: Boolean =
    exclude.isEmpty && include.forall(glob =>
      glob.indexOf('*') < 0 || isRecursiveGlob(glob)
    )

  def staticPaths(workspace: Path): Option[List[Path]] =
    if (isStatic) {
      Some(
        include.map(relpath => workspace.resolve(stripRecursiveGlob(relpath)))
      )
    } else {
      None
    }

  /**
   * Returns a source directory if this target uses rglobs("*.scala") */
  def sourceDirectory(workspace: Path): Option[Path] =
    include match {
      case head :: Nil if exclude.isEmpty =>
        PantsGlobs.rglobsSuffixes.collectFirst {
          case suffix if head.endsWith(suffix) =>
            workspace.resolve(head.stripSuffix(suffix))
        }
      case _ =>
        None
    }
}

object PantsGlobs {
  private val rglobsSuffixes = List(
    "/**/*.java",
    "/**/*.scala"
  )

  def fromJson(target: Value): PantsGlobs = {
    target.obj.get("globs") match {
      case Some(obj: Obj) =>
        val include = globsFromObject(obj)
        val exclude = obj.value.get("exclude") match {
          case Some(arr: Arr) =>
            arr.value.iterator.flatMap(globsFromObject).toList
          case _ =>
            Nil
        }
        PantsGlobs(include, exclude)
      case _ =>
        PantsGlobs(Nil, Nil)
    }
  }

  private def globsFromObject(value: Value): List[String] =
    value match {
      case Obj(obj) =>
        obj.get("globs") match {
          case Some(arr: Arr) =>
            arr.value.iterator.collect {
              case Str(glob) => glob
            }.toList
          case _ =>
            Nil
        }
      case _ => Nil
    }

  private def walkDepth(globs: List[String]): Option[Int] = {
    if (globs.isEmpty) Some(0)
    else {
      val depth = globs.map { glob =>
        if (glob.contains("**")) Int.MaxValue
        else glob.count(_ == '/') + 1
      }.max
      if (depth == Int.MaxValue) None
      else Some(depth)
    }
  }
}
