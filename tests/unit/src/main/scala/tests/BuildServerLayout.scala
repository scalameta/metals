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
  ): String = apply(sourceLayout, scalaVersion, "")

  def apply(
      sourceLayout: String,
      scalaVersion: String,
      directory: String,
  ): String = {
    s"""|$directory/project/build.properties
        |sbt.version=${V.sbtVersion}
        |$directory/build.sbt
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

object BazelBuildLayout extends BuildToolLayout {

  override def apply(sourceLayout: String, scalaVersion: String): String =
    s"""|/WORKSPACE
        |${workspaceFileLayout(scalaVersion)}
        |$sourceLayout
        |""".stripMargin

  def apply(
      sourceLayout: String,
      scalaVersion: String,
      bazelVersion: String,
  ): String =
    s"""|/.bazelversion
        |$bazelVersion
        |${apply(sourceLayout, scalaVersion)}
        |""".stripMargin

  def workspaceFileLayout(scalaVersion: String): String =
    s"""|# WORKSPACE
        |load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
        |
        |http_archive(
        |    name = "bazel_skylib",
        |    sha256 = "b8a1527901774180afc798aeb28c4634bdccf19c4d98e7bdd1ce79d1fe9aaad7",
        |    urls = [
        |        "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
        |        "https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
        |    ],
        |)
        |
        |# See https://github.com/bazelbuild/rules_scala/releases for up to date version information.
        |http_archive(
        |    name = "io_bazel_rules_scala",
        |    sha256 = "9a23058a36183a556a9ba7229b4f204d3e68c8c6eb7b28260521016b38ef4e00",
        |    strip_prefix = "rules_scala-6.4.0",
        |    url = "https://github.com/bazelbuild/rules_scala/releases/download/v6.4.0/rules_scala-v6.4.0.tar.gz",
        |)
        |
        |load("@io_bazel_rules_scala//:scala_config.bzl", "scala_config")
        |# Stores Scala version and other configuration
        |# 2.12 is a default version, other versions can be use by passing them explicitly:
        |# scala_config(scala_version = "2.11.12")
        |# Scala 3 requires extras...
        |#   3.2 should be supported on master. Please note that Scala artifacts for version (3.2.2) are not defined in
        |#   Rules Scala, they need to be provided by your WORKSPACE. You can use external loader like
        |#   https://github.com/bazelbuild/rules_jvm_external
        |scala_config(scala_version = "$scalaVersion")
        |
        |load("@io_bazel_rules_scala//scala:scala.bzl", "rules_scala_setup", "rules_scala_toolchain_deps_repositories")
        |
        |# loads other rules Rules Scala depends on 
        |rules_scala_setup()
        |
        |# Loads Maven deps like Scala compiler and standard libs. On production projects you should consider 
        |# defining a custom deps toolchains to use your project libs instead 
        |rules_scala_toolchain_deps_repositories(fetch_sources = True)
        |
        |load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
        |rules_proto_dependencies()
        |rules_proto_toolchains()
        |
        |load("@io_bazel_rules_scala//scala:toolchains.bzl", "scala_register_toolchains")
        |scala_register_toolchains()
        |
        |# optional: setup ScalaTest toolchain and dependencies
        |load("@io_bazel_rules_scala//testing:scalatest.bzl", "scalatest_repositories", "scalatest_toolchain")
        |scalatest_repositories()
        |scalatest_toolchain()
        |""".stripMargin
}
