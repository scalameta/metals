package scala.meta.internal.pantsbuild

import scala.meta.io.AbsolutePath
import scala.collection.mutable
import java.net.URI
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import scala.collection.JavaConverters._

object PantsConfiguration {

  /**
   * Converts a Pants target into a Bloop BSP target URI.
   *
   * Copy-pasted from https://github.com/scalacenter/bloop/blob/0bb8e1c2750c555f6414165d90f769dd52d105b8/frontend/src/main/scala/bloop/bsp/ProjectUris.scala#L32
   */
  def toBloopBuildTarget(
      projectBaseDir: AbsolutePath,
      id: String
  ): BuildTargetIdentifier = {
    val existingUri = projectBaseDir.toNIO.toUri
    val uri = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${id}",
      existingUri.getFragment
    )
    new BuildTargetIdentifier(uri.toString())
  }

  /** Returns the nearest enclosing directory of a Pants target */
  def baseDirectory(workspace: AbsolutePath, target: String): AbsolutePath = {
    workspace.resolve(target.substring(0, target.lastIndexOf(':')))
  }

  def pantsTargetsFromGson(
      elem: JsonElement,
      original: Option[JsonElement] = None
  ): Either[String, List[String]] = {
    def typeMismatch =
      "Unexpected 'pants-targets' configuration. " +
        "Expected a string or a list of strings." +
        s" Obtained: ${original.getOrElse(elem)}"
    if (elem.isJsonPrimitive()) {
      val array = new JsonArray()
      array.add(elem)
      pantsTargetsFromGson(array, Some(elem))
    } else if (elem.isJsonArray()) {
      val parsed = elem.getAsJsonArray().asScala.map { e =>
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
          Right(e.getAsString().split(" +").toList)
        } else {
          Left(elem)
        }
      }
      val notStrings = parsed.collect {
        case Left(e) => e
      }
      if (notStrings.nonEmpty) {
        Left(typeMismatch)
      } else {
        Right(parsed.collect { case Right(x) => x }.toList.flatten)
      }
    } else {
      Left(typeMismatch)
    }
  }

  def targetsFromSpaceSeparatedString(string: String): List[String] =
    string.split(" +").filter(_.nonEmpty).toList

  /**
   * Returns the toplevel directories that enclose all of the target.
   *
   * For example, this method returns the directories `a/src` and `b` given the
   * targets below:
   *
   * - a/src:foo
   * - a/src/inner:bar
   * - b:b
   * - b/inner:c
   */
  def sourceRoots(
      workspace: AbsolutePath,
      pantsTargets: List[String]
  ): List[AbsolutePath] = {
    val parts = pantsTargets.map(_.replaceFirst("/?:.*", "")).sorted
    if (parts.isEmpty) return Nil
    val buf = mutable.ListBuffer.empty[String]
    var current = parts(0)
    buf += current
    parts.iterator.drop(1).foreach { target =>
      if (!target.startsWith(current)) {
        current = target
        buf += current
      }
    }
    buf.result().map(workspace.resolve)
  }
}
