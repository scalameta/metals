package tests.debug

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import tests.BaseDapSuite
import tests.QuickBuildInitializer
import tests.QuickBuildLayout

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugProtocolCancelationSuite
    extends BaseDapSuite(
      "debug-protocol",
      QuickBuildInitializer,
      QuickBuildLayout,
    ) {

  test("start") {
    cleanWorkspace()
    client.onBeginSlowTask = (message, cancelParams) => {
      if (message == "Starting debug server") {
        server.fullServer.didCancelWorkDoneProgress(cancelParams)
      }
    }
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )
    mainClass.setEnvironmentVariables(List("HELLO=Foo").asJava)
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    val foo = sys.props.getOrElse("property", "")
           |    val bar = args(0)
           |    val env = sys.env.get("HELLO")
           |    print(foo + bar)
           |    env.foreach(print)
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      expectedMsg = "The task has been cancelled"
      debuggerMsg <- server
        .startDebugging(
          "a",
          DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
          mainClass,
        )
        .map(_ => "Debugger has been started")
        .recover { case _: ResponseErrorException =>
          expectedMsg
        }
    } yield assertNoDiff(debuggerMsg, expectedMsg)
  }

}
