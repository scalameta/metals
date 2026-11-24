package scala.meta.internal.metals.mcp

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.StatusCode

/** `Left` is a tool error, `Right` is the compilation report. */
final class McpCompiler(
    workspace: AbsolutePath,
    compilations: Compilations,
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    maxDiagnostics: Int,
)(implicit ec: ExecutionContext) {

  def compileAll(): Future[Either[String, String]] = {
    val allTargets = buildTargets.allBuildTargetIds
    if (allTargets.isEmpty) Future.successful(Left(noModulesFound))
    else
      compilations.cascadeCompile(allTargets).map { _ =>
        val allDiagnostics = diagnostics.allDiagnostics
        val output = allDiagnostics.show(workspace, maxDiagnostics)
        Right(
          if (output.isEmpty) "Compilation successful."
          else if (allDiagnostics.hasErrors)
            s"Compilation failed with errors:\n$output"
          else s"Compilation successful with warnings:\n$output"
        )
      }
  }

  def compileFile(path: AbsolutePath): Future[Either[String, String]] =
    if (!path.exists)
      Future.successful(Left(s"Error: File not found: $path"))
    else
      compilations.compileFile(path).map { compileResult =>
        buildTargets.inverseSources(path) match {
          case None =>
            Left(
              s"Error: no build target for $path, try running `import-build`."
            )
          case Some(buildTarget) =>
            Right(
              fileReport(path)
                .orElse(
                  moduleReport(buildTarget)(issues =>
                    s"No issues in the file, but found compile $issues in the module"
                  )
                )
                .orElse(upstreamErrors(buildTarget, "file"))
                .getOrElse(noIssues(compileResult))
            )
        }
      }

  def compileModule(module: String): Future[Either[String, String]] = {
    val targets = (buildTargets.allScala ++ buildTargets.allJava).toList
    targets.find(_.displayName == module) match {
      case Some(target) =>
        compilations.compileTarget(target.id).map { compileResult =>
          Right(
            moduleReport(target.id)(issues => s"Found $issues in the module")
              .orElse(upstreamErrors(target.id, "module"))
              .getOrElse(noIssues(compileResult))
          )
        }
      case None if targets.isEmpty =>
        Future.successful(Left(noModulesFound))
      case None =>
        Future.successful(
          Left(s"Error: Module not found: $module, see `list-modules`.")
        )
    }
  }

  private val noModulesFound =
    "Error: no modules found, try running `import-build`."

  private def fileReport(path: AbsolutePath): Option[String] = {
    val fileDiagnostics = diagnostics.forFile(path)
    val output = fileDiagnostics.show(maxDiagnostics)
    Option.when(output.nonEmpty)(
      s"Found ${issues(fileDiagnostics.hasErrors)} in $path:\n$output"
    )
  }

  private def moduleReport(
      buildTarget: BuildTargetIdentifier
  )(header: String => String): Option[String] = {
    val moduleDiagnostics = diagnostics.allDiagnostics.filter {
      case (path, _) =>
        buildTargets.inverseSources(path).contains(buildTarget)
    }
    val output = moduleDiagnostics.show(workspace, maxDiagnostics)
    Option.when(output.nonEmpty)(
      s"${header(issues(moduleDiagnostics.hasErrors))}:\n$output"
    )
  }

  private def upstreamErrors(
      buildTarget: BuildTargetIdentifier,
      fileOrModule: String,
  ): Option[String] = {
    val upstreamModules =
      diagnostics.upstreamTargetsWithCompilationErrors(buildTarget)
    Option.when(upstreamModules.nonEmpty) {
      val modules = upstreamModules
        .flatMap(buildTargets.jvmTarget)
        .map(_.displayName)
        .mkString("\n", "\n", "")
      s"Failed to compile the $fileOrModule, since there compile errors in upstream modules: $modules"
    }
  }

  private def issues(hasErrors: Boolean): String =
    if (hasErrors) "errors" else "warnings"

  private def noIssues(compileResult: CompileResult): String =
    if (compileResult.getStatusCode == StatusCode.CANCELLED)
      "Compilation cancelled."
    else "Compilation successful."
}
