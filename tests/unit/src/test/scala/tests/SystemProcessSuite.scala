package tests

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Properties

import scala.meta.internal.process.ProcessOutput
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import munit.FunSuite

class SystemProcessSuite extends FunSuite {

  implicit val ctx: ExecutionContext = this.munitExecutionContext

  test("exit code") {
    val cmd =
      if (Properties.isWin)
        List("cmd.exe", "/c", "exit 22")
      else
        List("/bin/sh", "-c", "exit 22")

    val ps = SystemProcess.run(
      cmd,
      AbsolutePath(sys.props.get("user.dir").get),
      redirectErrorOutput = false,
      Map.empty,
    )
    val exitCode = Await.result(ps.complete, 1.seconds)
    assertEquals(exitCode, 22)
  }

  test("cancel") {
    val cmd =
      if (Properties.isWin)
        List("ping", "-n", "10", "127.0.0.1")
      else
        List("sleep", "10")
    val ps = SystemProcess.run(
      cmd,
      AbsolutePath(sys.props.get("user.dir").get),
      redirectErrorOutput = false,
      Map.empty,
    )
    ps.cancel
    val exitCode = Await.result(ps.complete, 5.seconds)
    assert(exitCode != 0)
  }

  test("invalid cmd") {
    val ps = SystemProcess.run(
      List("absurd", "process"),
      AbsolutePath(sys.props.get("user.dir").get),
      redirectErrorOutput = false,
      Map.empty,
    )
    val exitCode = Await.result(ps.complete, 1.seconds)
    assertEquals(exitCode, 1)
  }

  test("capture-raw-stdout") {
    // The newline bytes would be dropped (and lines split) by the text reader;
    // `ProcessOutput.RawBytes` must return stdout byte-for-byte instead.
    assume(!Properties.isWin, "binary printf is POSIX-only")
    val expected = List[Byte]('a', '\n', 'b', '\n', 'c')
    val out = new ByteArrayOutputStream()
    val ps = SystemProcess.run(
      List("/bin/sh", "-c", "printf 'a\\nb\\nc'"),
      AbsolutePath(sys.props.get("user.dir").get),
      redirectErrorOutput = false,
      Map.empty,
      processOut = Some(ProcessOutput.RawBytes(out)),
    )
    val exitCode = Await.result(ps.complete, 5.seconds)
    assertEquals(exitCode, 0)
    assertEquals(out.toByteArray.toList, expected)
  }

  test("capture-raw-stdout-flushes-buffered-sink") {
    // A BufferedOutputStream holds its last chunk until flush/close. The reader
    // must flush the caller-owned sink on completion, so the bytes are readable
    // from the underlying stream without the caller flushing or closing it.
    assume(!Properties.isWin, "binary printf is POSIX-only")
    val underlying = new ByteArrayOutputStream()
    val buffered = new BufferedOutputStream(underlying)
    val ps = SystemProcess.run(
      List("/bin/sh", "-c", "printf 'abc'"),
      AbsolutePath(sys.props.get("user.dir").get),
      redirectErrorOutput = false,
      Map.empty,
      processOut = Some(ProcessOutput.RawBytes(buffered)),
    )
    val exitCode = Await.result(ps.complete, 5.seconds)
    assertEquals(exitCode, 0)
    assertEquals(underlying.toByteArray.toList, List[Byte]('a', 'b', 'c'))
  }

  test("raw-reader-closes-process-stdout") {
    val stdout = new TrackingInputStream("abc".getBytes)
    val stderr = new TrackingInputStream(Array.emptyByteArray)
    val ps = SystemProcess.wrapProcess(
      new FakeProcess(stdout, stderr),
      redirectErrorOutput = false,
      processOut = Some(ProcessOutput.RawBytes(new ByteArrayOutputStream())),
      processErr = None,
      discardInput = false,
      threadNamePrefix = "",
    )
    Await.result(ps.complete, 5.seconds)
    assert(stdout.closed, "raw reader must close the drained stdout stream")
  }
}

class TrackingInputStream(bytes: Array[Byte])
    extends ByteArrayInputStream(bytes) {
  @volatile var closed = false
  override def close(): Unit = {
    closed = true
    super.close()
  }
}

class FakeProcess(stdout: InputStream, stderr: InputStream) extends Process {
  override def getOutputStream: OutputStream = new ByteArrayOutputStream()
  override def getInputStream: InputStream = stdout
  override def getErrorStream: InputStream = stderr
  override def waitFor(): Int = 0
  override def exitValue(): Int = 0
  override def destroy(): Unit = ()
}
