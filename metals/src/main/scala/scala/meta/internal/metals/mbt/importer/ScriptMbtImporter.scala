package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtGlobMatcher
import scala.meta.internal.mtags.MD5
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

/**
 * Runs a user-defined exporter script and writes the resulting build JSON
 * to [[outputPath]].
 *
 * Supported script types:
 *  - `.mbt.scala` / `.mbt.java` — run with Scala CLI
 *  - `.mbt.sh`                  — run with `sh`
 *  - `.mbt.bat`
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
        buildCommand(workspace),
        workspace,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        additionalEnv = Map(
          "MBT_OUTPUT_FILE" -> out.toString,
          "MBT_WORKSPACE" -> workspace.toString,
        ),
      )
      .future
      .flatMap {
        case ExitCodes.Success =>
          ScriptMbtImporter.setWatchedFiles(
            scriptPath,
            MbtBuild.fromFile(out.toNIO).getWatchedFiles.asScala.toList,
          )
          Future.successful(())
        case ExitCodes.Cancel =>
          Future.failed(
            new CancellationException(
              s"mbt-script: ${scriptPath.toFile.getName()} was cancelled"
            )
          )
        case code =>
          Future.failed(
            new Exception(
              s"mbt-script: ${scriptPath.toFile.getName()} failed with exit code $code"
            )
          )
      }
  }

  override def isWatchedFile(path: AbsolutePath): Boolean = {
    val matchers = ScriptMbtImporter.watchedFiles(scriptPath)
    matchers.nonEmpty &&
    path.toRelativeInside(projectRoot).exists { relative =>
      matchers.exists(_.matcher.matches(relative.toNIO))
    }
  }

  override def digest(workspace: AbsolutePath): Option[String] = {
    val (exactPaths, globMatchers) =
      ScriptMbtImporter.watchedFiles(scriptPath).partition(!_.isGlob)
    val md = MessageDigest.getInstance("MD5")

    exactPaths.foreach(m =>
      Digest.digestFileBytes(workspace.resolve(m.pattern), md)
    )
    if (globMatchers.nonEmpty)
      workspace.listRecursive.foreach { f =>
        if (
          f.isFile && f
            .toRelativeInside(workspace)
            .exists(rel => globMatchers.exists(_.matcher.matches(rel.toNIO)))
        )
          Digest.digestFileBytes(f, md)
      }

    Some(MD5.bytesToHex(md.digest()))
  }

  override val projectRoot: AbsolutePath =
    Option(scriptPath.toNIO.getParent)
      .map(AbsolutePath(_))
      .getOrElse(scriptPath)

  private[importer] def buildCommand(workspace: AbsolutePath): List[String] = {
    if (scriptPath.toFile.getName().endsWith(".mbt.sh"))
      List("sh", scriptPath.toString)
    else if (scriptPath.toFile.getName().endsWith(".mbt.bat"))
      List(scriptPath.toString)
    else {
      // .mbt.scala or .mbt.java — use Scala CLI
      val launcher = userConfig().scalaCliLauncher.getOrElse("scala-cli")
      val scalaCliWorkspace = workspace.resolve(s".metals/scala-cli-$name")
      List(
        launcher,
        "run",
        "--server=false",
        "--workspace",
        scalaCliWorkspace.toString,
        scriptPath.toString,
      )
    }
  }
}

object ScriptMbtImporter {
  val scriptExtensions: List[String] =
    List(".mbt.scala", ".mbt.java", ".mbt.sh", ".mbt.bat")

  private val watchedFilesCache =
    TrieMap.empty[AbsolutePath, List[MbtGlobMatcher]]

  private[importer] def setWatchedFiles(
      scriptPath: AbsolutePath,
      rawPatterns: List[String],
  ): Unit = watchedFilesCache(scriptPath) =
    rawPatterns.map(MbtGlobMatcher.fromPattern)

  private[importer] def watchedFiles(
      scriptPath: AbsolutePath
  ): List[MbtGlobMatcher] =
    watchedFilesCache.getOrElse(scriptPath, Nil)

}
