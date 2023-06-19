package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import ch.epfl.scala.bsp4j.BspConnectionDetails
import scala.meta.internal.bsp.BspServers

case class BazelBuildTool(userConfig: () => UserConfiguration)
    extends BuildTool
    with BuildServerProvider {

  override def digest(workspace: AbsolutePath): Option[String] = {
    BazelDigest.current(workspace)
  }

  def createBspFileArgs(workspace: AbsolutePath): List[String] =
    BazelBuildTool.coursierArgs

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    workspace.listRecursive.exists {
      case file if file.filename == "WORKSPACE" => true
      case _ => false
    }
  }

  override def minimumVersion: String = "1.0.0"

  override def recommendedVersion: String = version

  override def version: String = "2.7.1"

  override def toString: String = "Bazel"

  override def executableName = "bazel"
}

object BazelBuildTool {
  private val coursierArgs = List(
    "cs", "launch", "org.jetbrains.bsp:bazel-bsp:2.7.1", "-M",
    "org.jetbrains.bsp.bazel.install.Install",
  )

  def writeBazelConfig(
      shellRunner: ShellRunner,
      projectDirectory: AbsolutePath,
      bspServers: BspServers,
  )(implicit
      ec: ExecutionContext
  ): Future[Either[Error, BspConnectionDetails]] = {
    def run() =
      shellRunner.run("Bazel-BSP config", coursierArgs, projectDirectory, false)
    run()
      .flatMap { code =>
        scribe.info(s"Generate Bazel-BSP process returned code $code")
        if (code != 0) run()
        else Future.successful(0)
      }
      .map { code =>
        if (code != 0)
          Left(new Error("Failed to write Bazel-BSP config to .bsp"))
        else
          bspServers
            .findAvailableServers()
            .collectFirst {
              case details if details.getName == "bazelbsp" => details
            }
            .toRight(new Error("'.bsp/bazelbsp.json' not found."))
      }
  }
}
