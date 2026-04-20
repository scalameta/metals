package scala.meta.internal.builds

import scala.util.matching.UnanchoredRegex

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class ScalaRulesetFinderHeuristic private (
    workspaceFile: Option[AbsolutePath],
    moduleFile: Option[AbsolutePath],
) {
  private lazy val workspaceFileContent = workspaceFile.flatMap(_.readTextOpt)
  private lazy val moduleFileContent = moduleFile.flatMap(_.readTextOpt)

  def guessRulesetName(): Option[String] = {
    import ScalaRulesetFinderHeuristic._
    // First try the module file, because it is the default since Bazel 7
    val fromModuleFile = moduleFileContent.collect {
      // The name of the repo that contains the module from which we load the "scala_config" extension
      case useExtensionPattern(repoName) => repoName
      // The name of the module which looks like it might contain Scala rules
      case rulesScalaDepPattern(moduleName)
          if moduleName.contains("rules_scala") =>
        moduleName
      // If there are no better clues, try finding a name that's typically used in examples on the Web
      case commonlyUsedRepoNames(name) => name
    }
    // If the module file doesn't exist or doesn't contain any clues, try the workspace file
    fromModuleFile.orElse(workspaceFileContent.collect {
      // The name of the repo from which we load the "scala_config" repository rule
      case loadRepoPattern(repoName) => repoName
      // If there are no better clues, try finding a name that's typically used in examples on the Web
      case commonlyUsedRepoNames(name) => name
    })
  }
}

object ScalaRulesetFinderHeuristic {
  // Copied from com.google.devtools.build.lib.cmdlineRepositoryName at https://github.com/bazelbuild/bazel
  private val repoNameCapturingGroup = "([a-zA-Z0-9][-.\\w]*)"
  private val moduleNameCapturingGroup = "([a-z](?:[a-z0-9._-]*[a-z0-9])?)"

  // These patterns are made public for the sake of testing.
  val useExtensionPattern: UnanchoredRegex =
    ("""use_extension\s*\(\s*"@""" + repoNameCapturingGroup + """//(?:\S+)"\s*,\s*"scala_config"""").r.unanchored
  val rulesScalaDepPattern: UnanchoredRegex =
    ("""bazel_dep\s*\(name\s*=\s*"""" + moduleNameCapturingGroup + "\"").r.unanchored
  val loadRepoPattern: UnanchoredRegex =
    ("""load\s*\(\s*"@""" + repoNameCapturingGroup + """//(?:\S+)"\s*,\s*"scala_config"""").r.unanchored
  val commonlyUsedRepoNames: UnanchoredRegex =
    "@((?:io_bazel_)?rules_scala)".r.unanchored

  def apply(workspace: AbsolutePath): ScalaRulesetFinderHeuristic = {
    val workspaceFile = (for {
      fileName <- List("WORKSPACE.bazel", "WORKSPACE")
      file = workspace.resolve(fileName) if file.isFile
    } yield file).headOption
    val moduleFile = Some(workspace.resolve("MODULE.bazel")).filter(_.isFile)
    new ScalaRulesetFinderHeuristic(workspaceFile, moduleFile)
  }
}
