package tests

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Properties

import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import munit.FunSuite

class SystemProcessSuite extends FunSuite {

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
      Map.empty
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
      Map.empty
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
      Map.empty
    )
    val exitCode = Await.result(ps.complete, 1.seconds)
    assertEquals(exitCode, 1)
  }
}
