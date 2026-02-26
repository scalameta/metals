package scala.meta.internal.builds.bazelnative

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

class BazelNativeProcess(
    workspace: AbsolutePath,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {

  private val activeProcess = new AtomicReference[Option[SystemProcess]](None)

  private def bazelBinary: String =
    userConfig().bazelNativePath.getOrElse("bazel")

  def build(
      targets: List[String],
      besPort: Int,
      extraFlags: List[String] = Nil,
      onStderr: String => Unit = _ => (),
  ): Future[Int] = {
    val cmd = List(
      bazelBinary,
      "build",
      s"--bes_backend=grpc://localhost:$besPort",
      "--bes_lifecycle_events",
      "--build_event_publish_all_actions",
      "--curses=no",
    ) ++ extraFlags ++ targets

    runProcess(cmd, onStderr)
  }

  def syncBuild(
      extraFlags: List[String] = Nil
  ): Future[Int] = {
    val cmd = List(bazelBinary, "build") ++ extraFlags ++ List("//...")
    runProcess(cmd)
  }

  def info(): Future[Map[String, String]] = {
    val output = new StringBuilder
    val cmd = List(bazelBinary, "info")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
      processErr = Some(_ => ()),
    )
    val ref = Some(process)
    activeProcess.set(ref)

    process.complete.map { _ =>
      activeProcess.compareAndSet(ref, None)
      output
        .toString()
        .linesIterator
        .flatMap { line =>
          val idx = line.indexOf(": ")
          if (idx > 0) Some(line.substring(0, idx) -> line.substring(idx + 2))
          else None
        }
        .toMap
    }
  }

  def version(): Future[String] = {
    val output = new StringBuilder
    val process = SystemProcess.run(
      List(bazelBinary, "--version"),
      workspace,
      redirectErrorOutput = true,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
    )

    process.complete.map { _ =>
      output.toString().trim.stripPrefix("bazel ").trim
    }
  }

  def shutdown(): Unit = {
    cancel()
    try {
      val process = SystemProcess.run(
        List(bazelBinary, "shutdown"),
        workspace,
        redirectErrorOutput = true,
        env = Map.empty,
      )
      scala.concurrent.Await.result(
        process.complete,
        scala.concurrent.duration.Duration(30, "s"),
      )
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative] Failed to shutdown bazel: ${e.getMessage}"
        )
    }
  }

  def cancel(): Unit =
    activeProcess.getAndSet(None).foreach(_.cancel)

  private def runProcess(
      cmd: List[String],
      onStderr: String => Unit = _ => (),
  ): Future[Int] = {
    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut = Some(_ => ()),
      processErr = Some(onStderr),
    )
    val ref = Some(process)
    activeProcess.set(ref)

    process.complete.map { exitCode =>
      activeProcess.compareAndSet(ref, None)
      exitCode
    }
  }
}
