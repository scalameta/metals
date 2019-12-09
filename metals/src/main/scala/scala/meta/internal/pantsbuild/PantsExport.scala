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
                deps <- target.get("targets").iterator
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
        val dependencies = value("targets").arr.map(_.str)
        val transitiveDependencies = value.get("transitive_targets") match {
          case None => computeTransitiveDependencies(name)
          case Some(transitiveDepencies) => transitiveDepencies.arr.map(_.str)
        }
        val libraries = value("libraries").arr.map(_.str)
        val isTargetRoot = value("is_target_root").bool &&
          !name.startsWith(".pants.d/gen")
        val id = value("id").str
        name -> PantsTarget(
          name = name,
          id = id,
          dependencies = dependencies,
          transitiveDependencies = transitiveDependencies,
          libraries = libraries,
          isTargetRoot = isTargetRoot,
          targetType = TargetType(value("target_type").str),
          pantsTargetType = PantsTargetType(value("pants_target_type").str)
        )
    }.toMap

    val allLibraries = output.obj("libraries").obj
    val libraries: Map[String, PantsLibrary] = allLibraries.iterator.map {
      case (name, valueObj) =>
        name -> PantsLibrary(name, valueObj.obj.map {
          case (key, value) =>
            key -> Paths.get(value.str)
        })
    }.toMap

    val scalaCompilerClasspath = output
      .obj("scala_platform")
      .obj("compiler_classpath")
      .arr
      .map(path => Paths.get(path.str))
    val scalaPlatform = PantsScalaPlatform(
      output.obj("scala_platform").obj("scala_version").str,
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
