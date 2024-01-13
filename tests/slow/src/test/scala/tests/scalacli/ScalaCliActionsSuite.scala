package tests.scalacli

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.mtags.BuildInfo.scalaCompilerVersion
import scala.meta.internal.mtags.CoursierComplete

import coursier.core.Version

class ScalaCliActionsSuite
    extends BaseScalaCLIActionSuite("actionableDiagnostic") {

  val oldOsLibVersion: Version = Version("0.7.8")
  val coursierComplete = new CoursierComplete(scalaCompilerVersion)
  val newestOsLib: String = coursierComplete
    .complete("com.lihaoyi::os-lib:")
    .headOption
    .map(_.stripPrefix(":"))
    .getOrElse("0.8.1")

  checkScalaCLI(
    "actionable-diagnostic-update",
    s"""|//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""|Change to: dep com.lihaoyi::os-lib:0.7.8
        |"os-lib is outdated, update to ${newestOsLib}"
        |     os-lib 0.7.8 -> com.lihaoyi::os-lib:${newestOsLib}
        |""".stripMargin,
    s"""|//> using lib "com.lihaoyi::os-lib:$newestOsLib"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    selectedActionIndex = 1,
  )

  checkScalaCLI(
    "actionable-diagnostic-didchange",
    s"""|//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""|Change to: dep com.lihaoyi::os-lib:0.7.8
        |"os-lib is outdated, update to ${newestOsLib}"
        |     os-lib 0.7.8 -> com.lihaoyi::os-lib:${newestOsLib}
        |""".stripMargin,
    s"""|// commentary
        |//> using lib "com.lihaoyi::os-lib:$newestOsLib"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    changeFile = f =>
      f.replace(
        s"""//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}""",
        s"""|// commentary
            |//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}""".stripMargin,
      ).stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    selectedActionIndex = 1,
  )

  checkNoActionScalaCLI(
    "actionable-diagnostic-didchange-stale-action-not-returned",
    s"""|//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    changeFile = f =>
      f.replace(
        s"""//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}""",
        s"""|//> using lib "<<>>com.lihaoyi::os-
            |//lib:${oldOsLibVersion.repr}""".stripMargin,
      ).stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
  )

  checkNoActionScalaCLI(
    "actionable-diagnostic-out-of-range",
    s"""|//> <<>>using lib "com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
  )

  checkScalaCLI(
    "auto-import",
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |
        |object A {
        |  <<Future>>.successful(2)
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |import scala.concurrent.Future
        |
        |object A {
        |  Future.successful(2)
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    fileName = "A.sc",
  )

  checkScalaCLI(
    "auto-import-shebang",
    s"""|#!/usr/bin/env -S scala-cli shebang
        |
        |//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |
        |object A {
        |  <<Future>>.successful(2)
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    s"""|#!/usr/bin/env -S scala-cli shebang
        |
        |//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |import scala.concurrent.Future
        |
        |object A {
        |  Future.successful(2)
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    fileName = "A.sc",
  )

}
