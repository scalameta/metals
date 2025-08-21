package tests

import java.util.Collections.emptyList
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsServerConfig
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.MessageActionItem

class DebugeeTimeoutSuite
    extends BaseDapSuite(
      "debugee-timeout",
      QuickBuildInitializer,
      QuickBuildLayout,
    ) {

  // Set a very short timeout to trigger timeout quickly in tests
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debuggeeStartTimeout = 1) // 1 second timeout

  test("debugee timeout - user chooses wait longer") {
    val userResponsePromise = Promise[MessageActionItem]()

    client.futureShowMessageRequestHandler = { params =>
      if (params.getMessage.contains("Debug session is taking longer than")) {
        Some(userResponsePromise.future)
      } else {
        None
      }
    }

    val mainClass = new ScalaMainClass(
      "a.SlowMain",
      emptyList(),
      emptyList(),
    )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/SlowMain.scala
           |package a
           |object SlowMain {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(3000)
           |    println("Finally started!")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )

      debugFuture = server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )

      _ <- Future(Thread.sleep(1500))
      _ = userResponsePromise.success(Messages.RequestTimeout.waitAction)

      debugger <- debugFuture
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput

    } yield {
      assert(output.contains("Finally started!"))
      assert(
        !server.headServer.tables.dismissedNotifications.DebugeeTimeout.isDismissed
      )
    }
  }

  test("debugee timeout - user chooses always wait") {
    val userResponsePromise = Promise[MessageActionItem]()

    client.futureShowMessageRequestHandler = { params =>
      if (params.getMessage.contains("Debug session is taking longer than")) {
        Some(userResponsePromise.future)
      } else {
        None
      }
    }

    val mainClass = new ScalaMainClass(
      "a.AnotherSlowMain",
      emptyList(),
      emptyList(),
    )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/AnotherSlowMain.scala
           |package a
           |object AnotherSlowMain {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(3000)
           |    println("Started with always wait!")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )

      debugFuture = server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )

      _ <- Future(Thread.sleep(1500))
      _ = userResponsePromise.success(Messages.RequestTimeout.waitAlways)

      debugger <- debugFuture
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput

    } yield {
      assert(output.contains("Started with always wait!"))
      assert(
        server.headServer.tables.dismissedNotifications.DebugeeTimeout.isDismissed
      )
    }
  }

  test("debugee timeout - user chooses cancel") {
    val userResponsePromise = Promise[MessageActionItem]()

    client.futureShowMessageRequestHandler = { params =>
      if (params.getMessage.contains("Debug session is taking longer than")) {
        Some(userResponsePromise.future)
      } else {
        None
      }
    }

    val mainClass = new ScalaMainClass(
      "a.CancelledMain",
      emptyList(),
      emptyList(),
    )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/CancelledMain.scala
           |package a
           |object CancelledMain {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(3000)
           |    println("This should not print!")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )

      debugFuture = server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )

      _ <- Future(Thread.sleep(1500))
      _ = userResponsePromise.success(Messages.RequestTimeout.cancel)

      result <- debugFuture.failed.map(_.getMessage)

    } yield {
      assert(result.contains("Debug session start cancelled by user after"))
      assert(
        !server.headServer.tables.dismissedNotifications.DebugeeTimeout.isDismissed
      )
    }
  }

  test("debugee timeout - already dismissed, no dialog shown") {
    server.headServer.tables.dismissedNotifications.DebugeeTimeout
      .dismissForever()
    assert(
      server.headServer.tables.dismissedNotifications.DebugeeTimeout.isDismissed
    )

    client.futureShowMessageRequestHandler = { params =>
      if (params.getMessage.contains("Debug session is taking longer than")) {
        fail("Dialog should not be shown when notification is dismissed")
      }
      None
    }

    val mainClass = new ScalaMainClass(
      "a.PreDismissedMain",
      emptyList(),
      emptyList(),
    )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/PreDismissedMain.scala
           |package a
           |object PreDismissedMain {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(3000)
           |    println("Started without dialog!")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )

      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput

    } yield {
      assert(output.contains("Started without dialog!"))
      assert(
        server.headServer.tables.dismissedNotifications.DebugeeTimeout.isDismissed
      )
    }
  }
}
