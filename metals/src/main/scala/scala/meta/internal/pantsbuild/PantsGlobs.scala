package scala.meta.internal.pantsbuild

import java.nio.file.Path
import ujson.Value
import ujson.Obj
import ujson.Arr
import ujson.Str

case class PantsGlobs(
    include: List[String],
    exclude: List[String]
) {

  /** Returns a source directory if this target uses rglobs("*.scala") */
  def sourceDirectory(workspace: Path): Option[Path] = include match {
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

  private def globsFromObject(value: Value): List[String] = value match {
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
}
