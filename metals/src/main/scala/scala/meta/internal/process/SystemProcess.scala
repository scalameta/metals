package scala.meta.internal.process

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.sys.process.BasicIO
import scala.util.control.NonFatal

import scala.meta.internal.ansi.AnsiFilter
import scala.meta.io.AbsolutePath

trait SystemProcess {
  def complete: Future[Int]
  def cancel: Unit
}

object SystemProcess {

  def run(
      cmd: List[String],
      cwd: AbsolutePath,
      redirectErrorOutput: Boolean,
      env: Map[String, String]
  ): SystemProcess = {

    try {
      val builder = new ProcessBuilder(cmd.asJava)
      builder.directory(cwd.toNIO.toFile)
      val envMap = builder.environment()
      envMap.putAll(env.asJava)

      builder.redirectErrorStream(redirectErrorOutput)
      val ps = builder.start()
      wrapProcess(ps, redirectErrorOutput)
    } catch {
      case NonFatal(e) =>
        scribe.error(s"Running process '${cmd.mkString(" ")}' failed", e)
        Failed
    }

  }

  def wrapProcess(
      ps: Process,
      redirectErrorOutput: Boolean
  ): SystemProcess = {
    def readOutput(stream: InputStream, f: String => Unit): Thread = {
      val filter = AnsiFilter()
      val thread = new Thread {
        override def run(): Unit = {
          // use scala.sys.process implementation
          try {
            BasicIO.processFully(line => f(filter(line)))(
              stream
            )
          } catch {
            case _: IOException => // that's ok, happens on cancel
            case NonFatal(e) =>
              scribe.error("Unexcepted error in reading out", e)
          }
        }
      }
      thread.setDaemon(true)
      thread.start()
      thread
    }
    // sbt might ask - Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
    // and stuck there
    ps.getOutputStream().close

    val outReaders = List(
      Some(readOutput(ps.getInputStream(), scribe.info(_))),
      if (redirectErrorOutput) None
      else Some(readOutput(ps.getErrorStream(), scribe.error(_)))
    ).flatten

    new SystemProcess {

      override def complete: Future[Int] = {
        Future {
          val exitCode = ps.waitFor()
          outReaders.foreach(_.join())
          exitCode
        }
      }

      override def cancel: Unit = {
        ps.destroy()
        val normalTermination = ps.waitFor(200, TimeUnit.MILLISECONDS)

        if (!normalTermination) {
          ps.destroyForcibly()
          ps.waitFor(200, TimeUnit.MILLISECONDS)
        }
        outReaders.foreach(_.interrupt())
      }
    }
  }

  val Failed: SystemProcess =
    new SystemProcess {
      override def complete: Future[Int] = Future.successful(1)
      override def cancel: Unit = ()
    }
}
