package tests.scalacli

import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.JsonParser._

import tests.BaseDapSuite
import tests.QuickBuildInitializer

class BreakpointScalaCliDapSuite
    extends BaseDapSuite(
      "scala-cli-debug-breakpoint",
      QuickBuildInitializer,
      ScalaCliBuildLayout,
    ) {

  override protected val retryTimes: Int = 3

  private val scalaCliScriptPath = "a/src/main/scala/a/script.sc"
  assertBreakpoints(
    "script",
    navigator =>
      server.startDebuggingUnresolved(
        DebugUnresolvedMainClassParams(
          "a.src.main.scala.a.script"
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
          "script"
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
