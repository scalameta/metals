package tests.scalacli

import tests.BuildToolLayout

object ScalaCliBuildLayout extends BuildToolLayout {
  override def apply(
      sourceLayout: String,
      scalaVersion: String,
  ): String = {
    s"""/.bsp/scala-cli.json
       |${BaseScalaCliSuite.scalaCliBspJsonContent(List("-S", scalaVersion))}
       |/.scala-build/ide-inputs.json
       |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
       |$sourceLayout
       |""".stripMargin
  }
}
