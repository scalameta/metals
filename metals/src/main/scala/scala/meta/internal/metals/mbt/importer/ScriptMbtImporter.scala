package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

/**
 * Runs a user-defined exporter script and writes the resulting build JSON
 * to [[outputPath]].
 *
 * Supported script types:
 *  - `.mbt.scala` / `.mbt.java` — run with Scala CLI
 *  - `.mbt.sh`                  — run with `sh`
 *
 * The script receives:
 *  - `MBT_OUTPUT_FILE` env var: path to write build JSON
 *  - `MBT_WORKSPACE`   env var: workspace root path
 */
final class ScriptMbtImporter(
    scriptPath: AbsolutePath,
    shellRunner: ShellRunner,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  override val name: String = {
    val fname = scriptPath.toFile.getName()
    ScriptMbtImporter.scriptExtensions
      .find(fname.endsWith(_))
      .map(ext => fname.stripSuffix(ext) + ext.replace(".mbt.", "-"))
      .getOrElse(fname)
  }

  override def extract(workspace: AbsolutePath): Future[Unit] = {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)
    shellRunner
      .run(
        s"mbt-script: ${scriptPath.toFile.getName()}",
        buildCommand,
        workspace,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        additionalEnv = Map(
          "MBT_OUTPUT_FILE" -> out.toString,
          "MBT_WORKSPACE" -> workspace.toString,
        ),
      )
      .map(_ => ())
  }

  override def isBuildRelated(path: AbsolutePath): Boolean = path == scriptPath

  override def digest(workspace: AbsolutePath): Option[String] =
    scala.util.Try(MD5.compute(scriptPath.toNIO)).toOption

  override val projectRoot: AbsolutePath =
    Option(scriptPath.toNIO.getParent)
      .map(AbsolutePath(_))
      .getOrElse(scriptPath)

  private[importer] def buildCommand: List[String] = {
    if (scriptPath.toFile.getName().endsWith(".mbt.sh"))
      List("sh", scriptPath.toString)
    else {
      // .mbt.scala or .mbt.java — use Scala CLI
      val launcher = userConfig().scalaCliLauncher.getOrElse("scala-cli")
      List(launcher, "run", scriptPath.toString)
    }
  }
}

object ScriptMbtImporter {
  val scriptExtensions: List[String] =
    List(".mbt.scala", ".mbt.java", ".mbt.sh")
}
