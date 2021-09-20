package scala.meta.internal.builds

import scala.concurrent.Future
import scala.concurrent.Promise
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
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

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
      additionalEnv: Map[String, String] = Map.empty,
      processOut: String => Unit = scribe.info(_),
      processErr: String => Unit = scribe.error(_),
      propagateError: Boolean = false,
      logInfo: Boolean = true
  ): Future[Int] = {
    val elapsed = new Timer(time)

    val env = additionalEnv ++ userConfig().javaHome.map("JAVA_HOME" -> _).toMap
    val ps = SystemProcess.run(
      args,
      directory,
      redirectErrorOutput,
      env,
      processOut,
      processErr,
      propagateError
    )
    // NOTE(olafur): older versions of VS Code don't respect cancellation of
    // window/showMessageRequest, meaning the "cancel build import" button
    // stays forever in view even after successful build import. In newer
    // VS Code versions the message is hidden after a delay.
    val taskResponse =
      languageClient.metalsSlowTask(
        new MetalsSlowTaskParams(commandRun)
      )

    val result = Promise[Int]
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        if (logInfo)
          scribe.info(s"user cancelled $commandRun")
        result.trySuccess(ExitCodes.Cancel)
        ps.cancel
      }
    }
    cancelables
      .add(() => ps.cancel)
      .add(() => taskResponse.cancel(false))

    val processFuture = ps.complete
    statusBar.trackFuture(
      s"Running '$commandRun'",
      processFuture
    )
    processFuture.map { code =>
      taskResponse.cancel(false)
      if (logInfo)
        scribe.info(s"time: ran '$commandRun' in $elapsed")
      result.trySuccess(code)
    }
    result.future
  }

}
