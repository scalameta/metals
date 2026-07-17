package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.collection.mutable

import scala.meta.internal.metals.mbt.MbtDependencyModule

/**
 * Recovers the generated jars of in-repo `java_proto_library` deps by probing
 * `bazel-bin/<pkg>/lib<proto_library_name>-<mode>.jar` (and the `-src.jar`
 * sibling) for the config-dependent `<mode>` ∈ {speed, lite, immutable},
 * attaching the modules to every target depending on the `java_proto_library`.
 */
object BazelGeneratedProtoModules {

  private val javaProtoLibraryRuleClass = "java_proto_library"

  // Compile mode in the generated jar name (`lib<name>-<mode>.jar`);
  // config-dependent, so probed against the filesystem rather than assumed.
  private val modes: List[String] = List("speed", "lite", "immutable")

  /**
   * The generated proto module ids each import target depends on, plus the
   * deduplicated modules themselves.
   *
   * @param targets         the import (scope) targets, in order
   * @param ruleClassesByTarget rule class per target (from the proto dump)
   * @param depsByTarget    direct `ruleInput` deps per target (from the proto
   *                        dump); walked transitively per target below
   * @param bazelBin        resolved `bazel info bazel-bin`, if any
   */
  def discover(
      targets: List[String],
      ruleClassesByTarget: Map[String, String],
      depsByTarget: Map[String, List[String]],
      bazelBin: Option[Path],
  ): Result =
    bazelBin match {
      case None => Result.empty
      case Some(bin) =>
        val modulesByJavaProto =
          mutable.Map.empty[String, List[MbtDependencyModule]]
        for {
          (label, ruleClass) <- ruleClassesByTarget
          if ruleClass == javaProtoLibraryRuleClass
        } {
          val modules = depsByTarget
            .getOrElse(label, Nil)
            .filter(isInRepoLabel)
            .flatMap(generatedModule(bin, _))
          if (modules.nonEmpty) modulesByJavaProto(label) = modules
        }
        // Skip the reachability walk below when there's no java_proto_library
        // at all — most workspaces have none.
        if (modulesByJavaProto.isEmpty) Result.empty
        else {
          val moduleIdsByTarget = mutable.Map.empty[String, Set[String]]
          for (target <- targets) {
            val ids = BazelTargetsProtoDump
              .reachableFrom(target, depsByTarget)
              .flatMap(modulesByJavaProto.getOrElse(_, Nil))
              .map(_.id)
              .toSet
            if (ids.nonEmpty) moduleIdsByTarget(target) = ids
          }

          Result(
            moduleIdsByTarget.toMap,
            modulesByJavaProto.values.flatten.toSeq
              .distinctBy(_.id)
              .sortBy(_.id),
          )
        }
    }

  private def isInRepoLabel(label: String): Boolean =
    label.startsWith("//")

  /**
   * The generated module for an in-repo `proto_library` label, probing
   * `bazel-bin/<pkg>/lib<name>-<mode>.jar` for the first existing `<mode>`. The
   * sources jar is the sibling `<name>-<mode>-src.jar` (no `lib` prefix), set
   * only when it exists. Root-package labels (`//:name`) probe directly under
   * `bazel-bin`.
   */
  private def generatedModule(
      bazelBin: Path,
      protoLibraryLabel: String,
  ): Option[MbtDependencyModule] =
    for {
      (pkg, name) <- BazelLabels
        .splitLabel(protoLibraryLabel)
        .filter { case (_, name) => name.nonEmpty }
      packageDir =
        if (pkg.isEmpty) bazelBin
        else pkg.split('/').foldLeft(bazelBin)(_.resolve(_))
      mode <- modes.find(mode =>
        Files.isRegularFile(packageDir.resolve(s"lib$name-$mode.jar"))
      )
    } yield {
      val jar = packageDir.resolve(s"lib$name-$mode.jar")
      val sources = packageDir.resolve(s"$name-$mode-src.jar")
      MbtDependencyModule(
        id = s"bazel-proto:$pkg:$name-$mode",
        jar = jar.toUri.toString,
        sources =
          if (Files.isRegularFile(sources)) sources.toUri.toString else null,
      )
    }

  case class Result(
      moduleIdsByTarget: Map[String, Set[String]],
      modules: Seq[MbtDependencyModule],
  )

  object Result {
    val empty: Result = Result(Map.empty, Nil)
  }

}
