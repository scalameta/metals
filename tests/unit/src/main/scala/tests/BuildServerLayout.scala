package tests

import scala.meta.internal.metals.{BuildInfo => V}

trait BuildToolLayout {
  def buildToolLayout(sourceLayout: String, scalaVersion: String): String
}

trait QuickBuildLayout extends BuildToolLayout {
  override def buildToolLayout(
      sourceLayout: String,
      scalaVersion: String
  ): String = {
    s"""|/metals.json
        |{ 
        |  "a": {"scalaVersion": "$scalaVersion"},  "b": {"scalaVersion": "$scalaVersion"} 
        |}
        |$sourceLayout
        |""".stripMargin
  }
}

trait SbtBuildLayout extends BuildToolLayout {
  val commonSbtSettings: String =
    """|import scala.concurrent.duration._
       |Global / serverIdleTimeout := Some(1 minute)
       |""".stripMargin

  override def buildToolLayout(
      sourceLayout: String,
      scalaVersion: String
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
