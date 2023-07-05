package tests.scalacli

import scala.meta.internal.metals.scalacli.ScalaCli

import tests.BuildToolLayout

object ScalaCliBuildLayout extends BuildToolLayout {
  override def apply(
      sourceLayout: String,
      scalaVersion: String,
  ): String = {
    s"""/.bsp/scala-cli.json
       |${ScalaCli.scalaCliBspJsonContent(List("-S", scalaVersion))}
       |/.scala-build/ide-inputs.json
       |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
       |$sourceLayout
       |""".stripMargin
  }
}
