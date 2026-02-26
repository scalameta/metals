package scala.meta.internal.builds.bazelnative

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

/**
 * Manages the lifecycle of locally-invoked `bazel` commands:
 * build, query, info, shutdown.
 */
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

  def query(expr: String): Future[String] = {
    val output = new StringBuilder
    val cmd = List(bazelBinary, "query", expr, "--output=label")
    scribe.debug(s"[BazelNative Process] Running: ${cmd.mkString(" ")}")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
      processErr =
        Some(line => scribe.debug(s"[BazelNative Process] query stderr: $line")),
    )
    activeProcess.set(Some(process))

    process.complete.map { exitCode =>
      activeProcess.set(None)
      scribe.debug(
        s"[BazelNative Process] query completed with exit code $exitCode"
      )
      output.toString().trim
    }
  }

  def info(): Future[Map[String, String]] = {
    val output = new StringBuilder
    val cmd = List(bazelBinary, "info")
    scribe.debug(s"[BazelNative Process] Running: ${cmd.mkString(" ")}")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
      processErr =
        Some(line => scribe.debug(s"[BazelNative Process] info stderr: $line")),
    )
    activeProcess.set(Some(process))

    process.complete.map { exitCode =>
      activeProcess.set(None)
      scribe.debug(
        s"[BazelNative Process] info completed with exit code $exitCode"
      )
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

  def aquery(expr: String): Future[String] = {
    val output = new StringBuilder
    val cmd =
      List(bazelBinary, "aquery", expr, "--output=jsonproto")
    scribe.debug(s"[BazelNative Process] Running: ${cmd.mkString(" ")}")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
      processErr = Some(line =>
        scribe.debug(s"[BazelNative Process] aquery stderr: $line")
      ),
    )
    activeProcess.set(Some(process))

    process.complete.map { exitCode =>
      activeProcess.set(None)
      scribe.debug(
        s"[BazelNative Process] aquery completed with exit code $exitCode"
      )
      output.toString().trim
    }
  }

  def version(): Future[String] = {
    val output = new StringBuilder
    val cmd = List(bazelBinary, "--version")
    scribe.debug(s"[BazelNative Process] Running: ${cmd.mkString(" ")}")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = true,
      env = Map.empty,
      processOut = Some(line => output.append(line).append("\n")),
    )

    process.complete.map { _ =>
      val raw = output.toString().trim
      raw.stripPrefix("bazel ").trim
    }
  }

  def shutdown(): Unit = {
    cancel()
    scribe.debug(s"[BazelNative Process] Shutting down bazel server")
    try {
      val process = SystemProcess.run(
        List(bazelBinary, "shutdown"),
        workspace,
        redirectErrorOutput = true,
        env = Map.empty,
        processOut =
          Some(line => scribe.debug(s"[BazelNative Process] shutdown: $line")),
      )
      process.complete
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative Process] Failed to shutdown bazel: ${e.getMessage}"
        )
    }
  }

  def cancel(): Unit = {
    activeProcess.getAndSet(None).foreach { process =>
      scribe.debug(s"[BazelNative Process] Cancelling active process")
      process.cancel
    }
  }

  private def runProcess(
      cmd: List[String],
      onStderr: String => Unit = _ => (),
  ): Future[Int] = {
    scribe.debug(s"[BazelNative Process] Running: ${cmd.mkString(" ")}")

    val process = SystemProcess.run(
      cmd,
      workspace,
      redirectErrorOutput = false,
      env = Map.empty,
      processOut =
        Some(line => scribe.debug(s"[BazelNative Process] stdout: $line")),
      processErr = Some { line =>
        scribe.debug(s"[BazelNative Process] stderr: $line")
        onStderr(line)
      },
    )
    activeProcess.set(Some(process))

    process.complete.map { exitCode =>
      activeProcess.set(None)
      scribe.debug(
        s"[BazelNative Process] command completed with exit code $exitCode"
      )
      exitCode
    }
  }
}
