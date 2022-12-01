package tests.debug

import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.JsonParser._

import tests.BaseDapSuite
import tests.QuickBuildInitializer
import tests.ScalaCliBuildLayout

class BreakpointScalaCliDapSuite
    extends BaseDapSuite(
      "scala-cli-debug-breakpoint",
      QuickBuildInitializer,
      ScalaCliBuildLayout,
    ) {

  private val scalaCliScriptPath = "a/src/main/scala/a/script.sc"
  assertBreakpoints(
    "script",
    navigator =>
      server.startDebuggingUnresolved(
        DebugUnresolvedMainClassParams(
          "a.src.main.scala.a.script_sc"
        ).toJson,
        navigator,
      ),
  )(
    source = s"""/$scalaCliScriptPath
                |val hello = "Hello"
                |val world = "World"
                |>>println("Hello!")
                |val helloWorld = s"$$hello $$world"
                |>>println(helloWorld)
                |System.exit(0)
                |""".stripMargin
  )

  private val scalaCliScriptPathTop = "script.sc"
  assertBreakpoints(
    "script-top",
    navigator =>
      server.startDebuggingUnresolved(
        DebugUnresolvedMainClassParams(
          "script_sc"
        ).toJson,
        navigator,
      ),
  )(
    source = s"""/$scalaCliScriptPathTop
                |val hello = "Hello"
                |val world = "World"
                |>>println("Hello!")
                |val helloWorld = s"$$hello $$world"
                |>>println(helloWorld)
                |System.exit(0)
                |""".stripMargin
  )

}
