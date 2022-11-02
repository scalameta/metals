package tests

import scala.meta.internal.metals.{BuildInfo => V}

sealed trait BuildToolLayout {
  def apply(sourceLayout: String, scalaVersion: String): String
}

object QuickBuildLayout extends BuildToolLayout {
  override def apply(
      sourceLayout: String,
      scalaVersion: String,
  ): String = {
    s"""|/metals.json
        |{ 
        |  "a": {"scalaVersion": "$scalaVersion"},  "b": {"scalaVersion": "$scalaVersion"} 
        |}
        |$sourceLayout
        |""".stripMargin
  }
}

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

object SbtBuildLayout extends BuildToolLayout {
  val commonSbtSettings: String =
    """|import scala.concurrent.duration._
       |Global / serverIdleTimeout := Some(1 minute)
       |""".stripMargin

  override def apply(
      sourceLayout: String,
      scalaVersion: String,
  ): String = {
    s"""|/project/build.properties
        |sbt.version=${V.sbtVersion}
        |/build.sbt
        |$commonSbtSettings
        |ThisBuild / scalaVersion := "$scalaVersion"
        |val a = project.in(file("a"))
        |val b = project.in(file("b"))
        |$sourceLayout
        |""".stripMargin
  }
}

object MillBuildLayout extends BuildToolLayout {
  override def apply(sourceLayout: String, scalaVersion: String): String =
    s"""|/build.sc
        |import mill._, scalalib._
        |
        |object MillMinimal extends ScalaModule {
        |  def scalaVersion = "${scalaVersion}"
        |}
        |$sourceLayout
        |""".stripMargin

  def apply(
      sourceLayout: String,
      scalaVersion: String,
      millVersion: String,
  ): String =
    s"""|/.mill-version
        |$millVersion
        |${apply(sourceLayout, scalaVersion)}
        |""".stripMargin
}
