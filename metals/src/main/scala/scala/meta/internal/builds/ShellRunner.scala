package scala.meta.internal.builds

import scala.concurrent.Future
import scala.util.Properties

import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessHandler
import scala.meta.io.AbsolutePath

import com.zaxxer.nuprocess.NuProcessBuilder
import coursierapi._

class ShellRunner(
    languageClient: MetalsLanguageClient,
    userConfig: () => UserConfiguration,
    time: Time,
    statusBar: StatusBar
)(implicit
    executionContext: scala.concurrent.ExecutionContext
) extends Cancelable {

  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = {
    cancelables.cancel()
  }

  def runJava(
      dependency: Dependency,
      main: String,
      dir: AbsolutePath,
      arguments: List[String]
  ): Future[Int] = {

    val classpathSeparator = if (Properties.isWin) ";" else ":"
    val classpath = Fetch
      .create()
      .withDependencies(dependency)
      .fetch()
      .asScala
      .mkString(classpathSeparator)

    val cmd = List(
      JavaBinary(userConfig().javaHome),
      "-classpath",
      classpath,
      main
    ) ::: arguments
    run(main, cmd, dir, redirectErrorOutput = false)
  }

  def run(
      commandRun: String,
      args: List[String],
      directory: AbsolutePath,
      redirectErrorOutput: Boolean,
      additionalEnv: Map[String, String] = Map.empty
  ): Future[Int] = {
    val elapsed = new Timer(time)
    val handler = ProcessHandler(
      joinErrorWithInfo = redirectErrorOutput
    )
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(directory.toNIO)
    userConfig().javaHome.foreach(pb.environment().put("JAVA_HOME", _))
    additionalEnv.foreach {
      case (key, value) =>
        pb.environment().put(key, value)
    }
    val runningProcess = pb.start()
    // NOTE(olafur): older versions of VS Code don't respect cancellation of
    // window/showMessageRequest, meaning the "cancel build import" button
    // stays forever in view even after successful build import. In newer
    // VS Code versions the message is hidden after a delay.
    val taskResponse =
      languageClient.metalsSlowTask(
        new MetalsSlowTaskParams(commandRun)
      )
    handler.response = Some(taskResponse)
    val processFuture = handler.completeProcess.future.map { result =>
      taskResponse.cancel(false)
      scribe.info(
        s"time: ran '$commandRun' in $elapsed"
      )
      result
    }
    statusBar.trackFuture(
      s"Running '$commandRun'",
      processFuture
    )
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        scribe.info(s"user cancelled $commandRun")
        handler.completeProcess.trySuccess(ExitCodes.Cancel)
        ProcessHandler.destroyProcess(runningProcess)
      }
    }
    cancelables
      .add(() => ProcessHandler.destroyProcess(runningProcess))
      .add(() => taskResponse.cancel(false))
    processFuture
  }
}
