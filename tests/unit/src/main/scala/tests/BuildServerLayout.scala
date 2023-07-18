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
    s"""|load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
        |
        |skylib_version = "1.0.3"
        |
        |http_archive(
        |    name = "bazel_skylib",
        |    sha256 = "1c531376ac7e5a180e0237938a2536de0c54d93f5c278634818e0efc952dd56c",
        |    type = "tar.gz",
        |    url = "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/{}/bazel-skylib-{}.tar.gz".format(skylib_version, skylib_version),
        |)
        |
        |http_archive(
        |    name = "io_bazel_rules_scala",
        |    sha256 = "77a3b9308a8780fff3f10cdbbe36d55164b85a48123033f5e970fdae262e8eb2",
        |    strip_prefix = "rules_scala-20220201",
        |    type = "zip",
        |    url = "https://github.com/bazelbuild/rules_scala/releases/download/20220201/rules_scala-20220201.zip",
        |)
        |
        |load("@io_bazel_rules_scala//:scala_config.bzl", "scala_config")
        |
        |scala_config(scala_version = "$scalaVersion")
        |
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repositories")
        |
        |scala_repositories()
        |
        |load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
        |
        |rules_proto_dependencies()
        |
        |rules_proto_toolchains()
        |
        |load("@io_bazel_rules_scala//scala:toolchains.bzl", "scala_register_toolchains")
        |
        |scala_register_toolchains()
        |""".stripMargin
}
