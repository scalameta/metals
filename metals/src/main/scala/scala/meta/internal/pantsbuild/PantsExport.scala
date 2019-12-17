package scala.meta.internal.pantsbuild

import java.nio.file.Paths
import scala.collection.mutable

case class PantsExport(
    targets: Map[String, PantsTarget],
    libraries: Map[String, PantsLibrary],
    scalaPlatform: PantsScalaPlatform,
    cycles: Cycles
)

object PantsExport {
  def fromJson(output: ujson.Value): PantsExport = {
    val allTargets = output.obj("targets").obj
    val transitiveDependencyCache = mutable.Map.empty[String, List[String]]
    def computeTransitiveDependencies(name: String): List[String] = {
      transitiveDependencyCache.getOrElseUpdate(
        name, {
          val isVisited = new mutable.LinkedHashSet[String]()
          def visit(n: String): Unit = {
            if (!isVisited(n)) {
              isVisited += n
              val target = allTargets(n).obj
              for {
                deps <- target.get(PantsKeys.targets).iterator
                dep <- deps.arr.iterator.map(_.str)
              } {
                visit(dep)
              }
            }
          }
          visit(name)
          isVisited.toList
        }
      )
    }
    val targets: Map[String, PantsTarget] = allTargets.iterator.map {
      case (name, valueObj) =>
        val value = valueObj.obj
        val dependencies = value(PantsKeys.targets).arr.map(_.str)
        val transitiveDependencies =
          value.get(PantsKeys.transitiveTargets) match {
            case None => computeTransitiveDependencies(name)
            case Some(transitiveDepencies) => transitiveDepencies.arr.map(_.str)
          }
        val libraries = value(PantsKeys.libraries).arr.map(_.str)
        val isTargetRoot = value(PantsKeys.isTargetRoot).bool &&
          !name.startsWith(".pants.d/gen")
        name -> PantsTarget(
          name = name,
          id = value(PantsKeys.id).str,
          dependencies = dependencies,
          transitiveDependencies = transitiveDependencies,
          libraries = libraries,
          isTargetRoot = isTargetRoot,
          targetType = TargetType(value(PantsKeys.targetType).str),
          pantsTargetType =
            PantsTargetType(value(PantsKeys.pantsTargetType).str)
        )
    }.toMap

    val allLibraries = output.obj(PantsKeys.libraries).obj
    val libraries: Map[String, PantsLibrary] = allLibraries.iterator.map {
      case (name, valueObj) =>
        name -> PantsLibrary(name, valueObj.obj.map {
          case (key, value) =>
            key -> Paths.get(value.str)
        })
    }.toMap

    val scalaCompilerClasspath = output
      .obj(PantsKeys.scalaPlatform)
      .obj(PantsKeys.compilerClasspath)
      .arr
      .map(path => Paths.get(path.str))
    val scalaPlatform = PantsScalaPlatform(
      output.obj(PantsKeys.scalaPlatform).obj(PantsKeys.scalaVersion).str,
      scalaCompilerClasspath
    )

    val cycles = Cycles.findConnectedComponents(output)

    PantsExport(
      targets = targets,
      libraries = libraries,
      scalaPlatform = scalaPlatform,
      cycles = cycles
    )
  }
}
