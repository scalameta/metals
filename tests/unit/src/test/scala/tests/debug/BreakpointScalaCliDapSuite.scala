package tests.debug

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
    main = Some("a.src.main.scala.a.script_sc"),
    buildTarget = Some("project_28a1ba13eb"),
  )(
    source = s"""/$scalaCliScriptPath
                |>>val hello = "Hello"
                |>>val world = "World"
                |val helloWorld = s"$$hello $$world"
                |>>println(helloWorld)
                |System.exit(0)
                |""".stripMargin
  )

  private val scalaCliScriptPathTop = "script.sc"
  assertBreakpoints(
    "script-top",
    main = Some("script_sc"),
    buildTarget = Some("project_28a1ba13eb"),
  )(
    source = s"""/$scalaCliScriptPathTop
                |>>val hello = "Hello"
                |>>val world = "World"
                |val helloWorld = s"$$hello $$world"
                |>>println(helloWorld)
                |System.exit(0)
                |""".stripMargin
  )

}
