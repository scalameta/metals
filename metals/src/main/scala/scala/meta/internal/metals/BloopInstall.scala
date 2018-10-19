package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient
import scala.meta.io.AbsolutePath
import scala.sys.process._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.compat.java8.FutureConverters._

object BloopInstall {
  def run(
      workspace: AbsolutePath,
      languageClient: LanguageClient,
      ec: ExecutionContext
  ): Cancelable = {
    val version = sbtVersion(workspace)
    val isValidSbtVersion = version.exists { v =>
      !v.startsWith("0") ||
      !v.startsWith("1.0") ||
      v.startsWith("1.1")
    }
    if (!isValidSbtVersion) {
      version.foreach { v =>
        val message =
          s"Unsupported sbt version $v. " +
            s"Please upgrade to sbt 1.2 to use Metals."
        languageClient.showMessage(
          new MessageParams(MessageType.Warning, message)
        )
      }
      Cancelable.empty
    } else {
      val tmp = bloopSbt()
      val plugins = AbsolutePath(System.getProperty("user.home"))
        .resolve(".sbt")
        .resolve("1.0")
        .resolve("plugins")
      Files.createDirectories(plugins.toNIO)
      def sanitize(s: String): String =
        fansi.Str(s).plainText
      val runningProcess = Process(
        List(
          "sbt",
          s"-addPluginSbtFile=$tmp",
          "bloopInstall",
          "exit"
        )
      ).run(
        ProcessLogger(
          out => scribe.info(sanitize(out)),
          out => scribe.error(sanitize(out))
        )
      )
      val params = new ShowMessageRequestParams()
      params.setMessage("Importing project... (this may take a while)")
      params.setType(MessageType.Info)
      val cancel = "Cancel"
      params.setActions(
        List(
          new MessageActionItem("OK"),
          new MessageActionItem(cancel),
        ).asJava
      )
      languageClient
        .showMessageRequest(params)
        .toScala
        .foreach { item =>
          if (item.getTitle == cancel) {
            runningProcess.destroy()
          }
        }(ec)
      Cancelable(() => runningProcess.destroy())
    }
  }

  private def bloopSbt(): Path = {
    val text =
      """|val bloopVersion = "1.0.0"
         |val bloopModule = "ch.epfl.scala" % "sbt-bloop" % bloopVersion
         |libraryDependencies := {
         |  import Defaults.sbtPluginExtra
         |  val oldDependencies = libraryDependencies.value
         |  val bloopArtifacts =
         |    oldDependencies.filter(d => d.organization == "ch.epfl.scala" && d.name == "sbt-bloop")
         |
         |  // Only add the plugin if it cannot be found in the current library dependencies
         |  if (!bloopArtifacts.isEmpty) oldDependencies
         |  else {
         |    val sbtVersion = (Keys.sbtBinaryVersion in pluginCrossBuild).value
         |    val scalaVersion = (Keys.scalaBinaryVersion in update).value
         |    val bloopPlugin = sbtPluginExtra(bloopModule, sbtVersion, scalaVersion)
         |    List(bloopPlugin) ++ oldDependencies
         |  }
         |}
         |""".stripMargin

    val tmp = Files.createTempDirectory("metals").resolve("bloop.sbt")
    Files.write(
      tmp,
      text.getBytes(StandardCharsets.UTF_8)
    )
    tmp
  }

  private def sbtVersion(workspace: AbsolutePath): Option[String] = {
    val props = new Properties()
    val buildproperties =
      workspace.resolve("project").resolve("build.properties")
    if (!buildproperties.isFile) None
    else {
      val in = Files.newInputStream(buildproperties.toNIO)
      try props.load(in)
      finally in.close()
      Option(props.getProperty("sbt.version"))
    }
  }

}
