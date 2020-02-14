package scala.meta.internal.pantsbuild

import java.nio.file.Paths
import scala.collection.mutable
import java.nio.file.Files

case class PantsExport(
    targets: Map[String, PantsTarget],
    libraries: Map[String, PantsLibrary],
    scalaPlatform: PantsScalaPlatform,
    cycles: Cycles
)

object PantsExport {
  def fromJson(args: Export, output: ujson.Value): PantsExport = {
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
    val targetsByDirectory = allTargets.keys.groupBy { name =>
      PantsConfiguration.baseDirectoryString(name)
    }
    val targets: Map[String, PantsTarget] = allTargets.iterator.map {
      case (name, valueObj) =>
        val value = valueObj.obj
        val directDependencies = value(PantsKeys.targets).arr.map(_.str)
        val syntheticDependencies: Iterable[String] =
          if (args.isMergeTargetsInSameDirectory) {
            targetsByDirectory
              .getOrElse(
                PantsConfiguration.baseDirectoryString(name),
                Nil
              )
              .filterNot(_ == name)
          } else {
            Nil
          }
        val dependencies = directDependencies ++ syntheticDependencies
        val excludes = (for {
          excludes <- value.get(PantsKeys.excludes).iterator
          value <- excludes.arr.iterator
        } yield value.str).toSet
        val transitiveDependencies: Seq[String] =
          value.get(PantsKeys.transitiveTargets) match {
            case None => computeTransitiveDependencies(name)
            case Some(transitiveDepencies) => transitiveDepencies.arr.map(_.str)
          }
        val libraries = value(PantsKeys.libraries).arr.map(_.str)
        val isPantsTargetRoot = value(PantsKeys.isTargetRoot).bool
        val pantsTargetType =
          PantsTargetType(value(PantsKeys.pantsTargetType).str)
        val targetType =
          if (pantsTargetType.isNodeModule) {
            // NOTE(olafur) Treat "node_module" targets as `target_type:
            // RESOURCE` since they are included on the runtime classpath even
            // if they have `target_type: SOURCE`. See
            // https://github.com/pantsbuild/pants/issues/9026 for a reason why
            // node_module needs special handling.
            TargetType("RESOURCE")
          } else {
            TargetType(value(PantsKeys.targetType).str)
          }
        name -> PantsTarget(
          name = name,
          id = value(PantsKeys.id).str,
          dependencies = dependencies,
          excludes = excludes,
          transitiveDependencies = transitiveDependencies,
          libraries = libraries,
          isPantsTargetRoot = isPantsTargetRoot,
          targetType = targetType,
          pantsTargetType = pantsTargetType,
          globs = PantsGlobs.fromJson(value),
          roots = PantsRoots.fromJson(value)
        )
    }.toMap

    val allLibraries = output.obj(PantsKeys.libraries).obj
    val libraries: Map[String, PantsLibrary] = allLibraries.iterator.map {
      case (name, valueObj) =>
        name -> PantsLibrary(name, valueObj.obj.flatMap {
          case (key, value) =>
            val path = Paths.get(value.str)
            if (Files.exists(path)) Some(key -> path)
            else None
        })
    }.toMap

    val cycles = Cycles.findConnectedComponents(targets)

    val scalaPlatform = PantsScalaPlatform.fromJson(output)

    PantsExport(
      targets = targets,
      libraries = libraries,
      scalaPlatform = scalaPlatform,
      cycles = cycles
    )
  }

}
