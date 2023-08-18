package tests

import scala.meta.internal.metals.{BuildInfo => V}

trait BuildToolLayout {
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
    apply(sourceLayout, scalaVersion, includeMunit = false)

  def apply(
      sourceLayout: String,
      scalaVersion: String,
      includeMunit: Boolean,
  ): String = {
    val munitModule =
      if (includeMunit)
        """|object test extends ScalaTests with TestModule.Munit {
           |    def ivyDeps = Agg(
           |      ivy"org.scalameta::munit::0.7.29"
           |    )
           |  }  
           |""".stripMargin
      else ""

    s"""|/build.sc
        |import mill._, scalalib._
        |
        |object MillMinimal extends ScalaModule {
        |  def scalaVersion = "${scalaVersion}"
        |  $munitModule
        |}
        |$sourceLayout
        |""".stripMargin
  }

  def apply(
      sourceLayout: String,
      scalaVersion: String,
      millVersion: String,
      includeMunit: Boolean = false,
  ): String =
    s"""|/.mill-version
        |$millVersion
        |${apply(sourceLayout, scalaVersion)}
        |${apply(sourceLayout, scalaVersion, includeMunit)}
        |""".stripMargin
}
