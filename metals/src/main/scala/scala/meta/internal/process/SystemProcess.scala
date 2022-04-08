package scala.meta.internal.process

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
  def inputStream: InputStream
  def outputStream: OutputStream
  def cancel: Unit
}

object SystemProcess {

  def run(
      cmd: List[String],
      cwd: AbsolutePath,
      redirectErrorOutput: Boolean,
      env: Map[String, String],
      processOut: Option[String => Unit] = Some(scribe.info(_)),
      processErr: Option[String => Unit] = Some(scribe.error(_)),
      propagateError: Boolean = false,
      discardInput: Boolean = true,
      threadNamePrefix: String = ""
  ): SystemProcess = {

    try {
      val builder = new ProcessBuilder(cmd.asJava)
      builder.directory(cwd.toNIO.toFile)
      val envMap = builder.environment()
      envMap.putAll(env.asJava)

      builder.redirectErrorStream(redirectErrorOutput)
      val ps = builder.start()
      wrapProcess(
        ps,
        redirectErrorOutput,
        processOut,
        processErr,
        discardInput,
        threadNamePrefix
      )
    } catch {
      case NonFatal(e) =>
        if (propagateError) throw e
        else {
          scribe.error(s"Running process '${cmd.mkString(" ")}' failed", e)
          Failed
        }
    }

  }

  def wrapProcess(
      ps: Process,
      redirectErrorOutput: Boolean,
      processOut: Option[String => Unit],
      processErr: Option[String => Unit],
      discardInput: Boolean,
      threadNamePrefix: String
  ): SystemProcess = {
    def readOutput(
        name: String,
        stream: InputStream,
        f: String => Unit
    ): Thread = {
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
      if (threadNamePrefix.nonEmpty)
        thread.setName(s"$threadNamePrefix-$name")
      thread.setDaemon(true)
      thread.start()
      thread
    }

    if (discardInput) {
      // sbt might ask - Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
      // and stuck there
      ps.getOutputStream().close
    }

    val outReaders = List(
      processOut.map(f => readOutput("stdout", ps.getInputStream(), f)),
      if (redirectErrorOutput) None
      else processErr.map(f => readOutput("stderr", ps.getErrorStream(), f))
    ).flatten

    new SystemProcess {

      override def complete: Future[Int] = {
        Future {
          val exitCode = ps.waitFor()
          outReaders.foreach(_.join())
          exitCode
        }
      }

      override def inputStream: InputStream =
        ps.getInputStream
      override def outputStream: OutputStream =
        ps.getOutputStream

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
      override def inputStream: InputStream =
        new InputStream {
          override def read() = -1
          override def read(b: Array[Byte], off: Int, len: Int) = -1
        }
      override def outputStream: OutputStream =
        new OutputStream {
          override def write(value: Int) = {}
          override def write(b: Array[Byte], off: Int, len: Int): Unit = {}
        }
      override def cancel: Unit = ()
    }
}
