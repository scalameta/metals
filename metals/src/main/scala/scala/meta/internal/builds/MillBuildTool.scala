package scala.meta.internal.builds
import scala.meta.internal.metals.{MetalsServerConfig, UserConfiguration}
import scala.meta.io.AbsolutePath
import scala.util.Properties
import java.nio.file.Files
import java.nio.file.Path

case class MillBuildTool() extends BuildTool {

  private val predefScript =
    s"import $$ivy.`com.lihaoyi::mill-contrib-bloop:$version`".getBytes()

  private val predefScriptName = "predef.sc"

  private lazy val predefScriptPath: Path = {
    Files.write(tempDir.resolve(predefScriptName), predefScript)
  }

  private lazy val embeddedMillWrapper: AbsolutePath = {
    val millWrapper =
      if (Properties.isWin) "millw.bat"
      else "millw"
    val out = BuildTool.copyFromResource(tempDir, millWrapper)
    out.toFile.setExecutable(true)
    AbsolutePath(out)
  }

  override def redirectErrorOutput: Boolean = true

  override def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String] = {
    val cmd = List(
      "--predef",
      predefScriptPath.toString,
      "mill.contrib.Bloop/install"
    )
    userConfig().millScript match {
      case Some(script) =>
        script :: cmd
      case None =>
        embeddedMillWrapper.toString() :: "--mill-version" :: version :: cmd
    }
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    MillDigest.current(workspace)

  override def minimumVersion: String = "0.4.0"

  override def version: String = "0.4.0"

  override def toString(): String = "Mill"

  def executableName = "mill"
}

object MillBuildTool {
  def isMillRelatedPath(workspace: AbsolutePath, path: AbsolutePath) = {
    val filename = path.toNIO.getFileName.toString
    filename.endsWith(".sc")
  }
}
