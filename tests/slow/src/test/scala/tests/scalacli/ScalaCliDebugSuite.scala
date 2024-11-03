package tests.scalacli

import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.scalacli.ScalaCli

import tests.BaseDapSuite
import tests.QuickBuildInitializer

class ScalaCliDebugSuite
    extends BaseDapSuite(
      "scala-cli-debug",
      QuickBuildInitializer,
      ScalaCliBuildLayout,
    ) {

  private val scalaCliScriptPath = "a/src/main/scala/a/main.sc"

  for (runType <- List("runOrTestFile", "run")) {

    test(s"run-scala-cli-script-$runType") {
      for {
        _ <- initialize(
          s"""/.bsp/scala-cli.json
             |${ScalaCli.scalaCliBspJsonContent()}
             |/.scala-build/ide-inputs.json
             |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
             |/$scalaCliScriptPath
             |print("oranges are nice")""".stripMargin
        )
        _ <- server.didOpen(scalaCliScriptPath)
        _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
        debugger <- server.startDebuggingUnresolved(
          DebugDiscoveryParams(
            server.toPath(scalaCliScriptPath).toURI.toString,
            runType,
          ).toJson
        )
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
        output <- debugger.allOutput
      } yield assertNoDiff(output, "oranges are nice")
    }
  }

}
