package tests.scalafix

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class ScalafixLintDiagnosticsSuite
    extends BaseLspSuite("scalafix-lint-diagnostics") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(scalafixLintEnabled = true)

  test("lint-diagnostics-published") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalaVersion": "${V.scala213}"}}
           |/.scalafix.conf
           |rules = [
           |  DisableSyntax
           |]
           |DisableSyntax.noVars = true
           |/a/src/main/scala/Main.scala
           |object Main {
           |  var x = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:2:3: warning: mutable state should be avoided
           |  var x = 1
           |  ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("lint-diagnostics-cleared-on-fix") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalaVersion": "${V.scala213}"}}
           |/.scalafix.conf
           |rules = [
           |  DisableSyntax
           |]
           |DisableSyntax.noVars = true
           |/a/src/main/scala/Main.scala
           |object Main {
           |  var x = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:2:3: warning: mutable state should be avoided
           |  var x = 1
           |  ^^^
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/Main.scala") { _ =>
        """|object Main {
           |  val x = 1
           |}
           |""".stripMargin
      }
      _ <- server.didSave("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        "",
      )
    } yield ()
  }

  test("lint-with-compiler-warnings") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalaVersion": "${V.scala213}"}}
           |/.scalafix.conf
           |rules = [
           |  DisableSyntax
           |]
           |DisableSyntax.noVars = true
           |/a/src/main/scala/Main.scala
           |sealed trait Animal
           |case class Dog(name: String) extends Animal
           |case class Cat(name: String) extends Animal
           |object Main {
           |  var x = 1
           |  def greet(a: Animal): String = a match {
           |    case Dog(n) => n
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:5:3: warning: mutable state should be avoided
           |  var x = 1
           |  ^^^
           |a/src/main/scala/Main.scala:6:34: warning: match may not be exhaustive.
           |It would fail on the following input: Cat(_)
           |  def greet(a: Animal): String = a match {
           |                                 ^
           |""".stripMargin,
      )
    } yield ()
  }

}
