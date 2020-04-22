package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths

import ujson.Obj

case class PantsRoots(
    sourceRoots: List[Path]
)

object PantsRoots {
  def fromJson(value: Obj): PantsRoots = {
    val sourceRoots = for {
      roots <- value.obj.get(PantsKeys.roots).toList
      root <- roots.arr
      sourceRoot <- root.obj.get(PantsKeys.sourceRoot)
    } yield Paths.get(sourceRoot.str)
    PantsRoots(sourceRoots)
  }
}
