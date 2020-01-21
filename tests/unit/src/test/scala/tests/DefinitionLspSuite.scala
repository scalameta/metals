package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

class DefinitionLspSuite extends BaseLspSuite("definition") {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      statistics = new StatisticsConfig("diagnostics")
    )

  test("definition") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { },
           |  "b": {
           |    "libraryDependencies": [
           |      "org.scalatest::scalatest:3.0.5"
           |    ],
           |    "dependsOn": [ "a" ]
           |  }
           |}
           |/a/src/main/java/a/Message.java
           |package a;
           |public class Message {
           |  public static String message = "Hello world!";
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |object Main extends App {
           |  val message = Message.message
           |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
           |  println(message)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |import org.scalatest.FunSuite
           |object MainSuite extends FunSuite {
           |  test("a") {
           |    val condition = Main.message.contains("Hello")
           |    assert(condition)
           |  }
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(server.workspaceDefinitions, "")
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |object Main/*L3*/ extends App/*App.scala*/ {
           |  val message/*L4*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*L4*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L4*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/("a") {
           |    val condition/*L6*/ = Main/*Main.scala:3*/.message/*Main.scala:4*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L6*/)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didChange("b/src/main/scala/a/MainSuite.scala") { text =>
        ">>>>>>>\n\n" + text.replaceFirst("\"a\"", "testName")
      }
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        ">>>>>>>\n\n" + text.replaceFirst("message", "helloMessage")
      }
      _ = assertNoDiff(
        // Check that:
        // - navigation works for all unchanged identifiers, even if the buffer doesn't parse
        // - line numbers have shifted by 2 for both local and Main.scala references in MainSuite.scala
        // - old references to `message` don't resolve because it has been renamed to `helloMessage`
        // - new references to like `testName` don't resolve
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |object Main/*L5*/ extends App/*App.scala*/ {
           |  val helloMessage/*<no symbol>*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*<no symbol>*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L6*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/(testName/*<no symbol>*/) {
           |    val condition/*L8*/ = Main/*Main.scala:5*/.message/*<no symbol>*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L8*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }

  // This test makes sure that textDocument/definition returns reference locations
  // instead of definition location if the symbol at the given text document position
  // represents a definition itself.
  // https://github.com/scalameta/metals/issues/755
  test("definition-fallback-to-show-usages") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {
          |    "dependsOn": [ "a" ]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val name = "John"
          |  def main() = {
          |    println(name)
          |  }
          |}
          |/b/src/main/scala/a/B.scala
          |package a
          |object B {
          |  def main() = {
          |    println(A.name)
          |  }
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/a/B.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |object A/*L1*/ {
           |  val name/*L2*/ = "John"
           |  def main/*L3*/() = {
           |    println/*Predef.scala*/(name/*L2*/)
           |  }
           |}
           |/b/src/main/scala/a/B.scala
           |package a
           |object B/*L1*/ {
           |  def main/*L2*/() = {
           |    println/*Predef.scala*/(A/*A.scala:1*/.name/*A.scala:2*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }

  // This test shows what happens when external dependency sources require compiler
  // plugins that are not enabled in the user's build. In this case, cats-core
  // requires org.scalamacros:macroparadise and io.spire-match:kind-projector.
  // Navigation continues to mostly work, except for areas that have compilation
  // errors.
  test("missing-compiler-plugin".flaky) {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "org.typelevel::cats-core:1.4.0"
          |    ]
          |  }
          |}
          |/a/src/main/scala/a/Main.scala
          |import cats._
          |import cats.implicits._
          |object Main {
          |  println(Contravariant[Show])
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions // trigger definition
      _ <- server.didOpen("cats/Contravariant.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/.metals/readonly/cats/Contravariant.scala
           |package cats
           |import simulacrum/*<no symbol>*/.typeclass/*L5*/
           |/**
           | * Must obey the laws defined in cats.laws.ContravariantLaws.
           | */
           |@typeclass/*<no symbol>*/ trait Contravariant/*L5*/[F/*L5*/[_]] extends Invariant/*Invariant.scala*/[F/*L5*/] { self/*L1*/ =>
           |  def contramap/*L6*/[A/*L6*/, B/*L6*/](fa/*L6*/: F/*L5*/[A/*L6*/])(f/*L6*/: B/*L6*/ => A/*L6*/): F/*L5*/[B/*L6*/]
           |  override def imap/*L7*/[A/*L7*/, B/*L7*/](fa/*L7*/: F/*L5*/[A/*L7*/])(f/*L7*/: A/*L7*/ => B/*L7*/)(fi/*L7*/: B/*L7*/ => A/*L7*/): F/*L5*/[B/*L7*/] = contramap/*L6*/(fa/*L7*/)(fi/*L7*/)
           |
           |  def compose/*L9*/[G/*L9*/[_]:/*unexpected: L5*/ Contravariant/*L5*/]: Functor/*Functor.scala*/[λ/*<no symbol>*/[α/*<no symbol>*/ => F/*<no symbol>*/[G/*<no symbol>*/[α/*<no symbol>*/]]]] =
           |    new ComposedContravariant/*Composed.scala*/[F/*L5*/, G/*L9*/] {
           |      val F/*L11*/ = self/*L5*/
           |      val G/*L12*/ = Contravariant/*<no symbol>*/[G/*L9*/]
           |    }
           |
           |  /**
           |   * Lifts natural subtyping contravariance of contravariant Functors.
           |   * could be implemented as contramap(identity), but the Functor laws say this is equivalent
           |   */
           |  def narrow/*L19*/[A/*L19*/, B/*L19*/ <: A/*L19*/](fa/*L19*/: F/*L5*/[A/*L19*/]): F/*L5*/[B/*L19*/] = fa/*L19*/.asInstanceOf/*<no symbol>*/[F/*L5*/[B/*L19*/]]
           |
           |  def liftContravariant/*L21*/[A/*L21*/, B/*L21*/](f/*L21*/: A/*L21*/ => B/*L21*/): F/*L5*/[B/*L21*/] => F/*L5*/[A/*L21*/] = contramap/*L6*/(_: F/*L5*/[B/*L21*/])(f/*L21*/)
           |
           |  override def composeFunctor/*L23*/[G/*L23*/[_]: Functor/*Functor.scala*/]: Contravariant/*L5*/[λ/*<no symbol>*/[α/*<no symbol>*/ => F/*<no symbol>*/[G/*<no symbol>*/[α/*<no symbol>*/]]]] =
           |    new ComposedContravariantCovariant/*Composed.scala*/[F/*L5*/, G/*L23*/] {
           |      val F/*L25*/ = self/*L5*/
           |      val G/*L26*/ = Functor/*Functor.scala*/[G/*L23*/]
           |    }
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |import cats._
           |import cats.implicits/*implicits.scala*/._
           |object Main/*L2*/ {
           |  println/*Predef.scala*/(Contravariant/*Contravariant.scala*/[Show/*Show.scala*/])
           |}
          """.stripMargin
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|.metals/readonly/cats/Contravariant.scala:2:8: information: not found: object simulacrum
           |import simulacrum.typeclass
           |       ^^^^^^^^^^
           |.metals/readonly/cats/Contravariant.scala:6:2: information: not found: type typeclass
           |@typeclass trait Contravariant[F[_]] extends Invariant[F] { self =>
           | ^^^^^^^^^
           |.metals/readonly/cats/Contravariant.scala:10:45: information: not found: type λ
           |  def compose[G[_]: Contravariant]: Functor[λ[α => F[G[α]]]] =
           |                                            ^
           |.metals/readonly/cats/Contravariant.scala:10:45: information: <error> takes no type parameters, expected: one
           |  def compose[G[_]: Contravariant]: Functor[λ[α => F[G[α]]]] =
           |                                            ^
           |.metals/readonly/cats/Contravariant.scala:13:15: information: not found: value Contravariant
           |      val G = Contravariant[G]
           |              ^^^^^^^^^^^^^
           |.metals/readonly/cats/Contravariant.scala:24:61: information: not found: type λ
           |  override def composeFunctor[G[_]: Functor]: Contravariant[λ[α => F[G[α]]]] =
           |                                                            ^
           |.metals/readonly/cats/Contravariant.scala:24:61: information: <error> takes no type parameters, expected: one
           |  override def composeFunctor[G[_]: Functor]: Contravariant[λ[α => F[G[α]]]] =
           |                                                            ^
           |""".stripMargin
      )
      _ <- server.didFocus("a/src/main/scala/a/Main.scala")
      // dependency diagnostics are unpublished.
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("stale") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/a/Main.scala
          |object Main {
          |  val x: Int = math.max(1, 2)
          |}
          |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |object Main/*L0*/ {
           |  val x/*L1*/: Int/*Int.scala*/ = math.max/*package.scala*/(1, 2)
           |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Main.scala")(
        _.replaceAllLiterally("max(1, 2)", "max")
      )
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |object Main/*L0*/ {
           |  val x/*L1*/: Int/*Int.scala*/ = math.max/*package.scala*/
           |}
           |""".stripMargin
      )
    } yield ()
  }

  test("annotations") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "compilerPlugins": [
          |      "org.scalamacros:::paradise:2.1.1"
          |    ],
          |    "libraryDependencies": [
          |      "io.circe::circe-core:0.9.0",
          |      "io.circe::circe-derivation:0.9.0-M4"
          |    ]
          |  }
          |}
          |/a/src/main/scala/a/User.scala
          |package a
          |import io.circe.derivation.JsonCodec
          |@JsonCodec case class User(name: String)
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  val user = User("John")
          |  val name = user.name
          |  val encoder = User.encodeUser
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main/*L1*/ {
          |  val user/*L2*/ = User/*User.scala:2*/("John")
          |  val name/*L3*/ = user/*L2*/.name/*User.scala:2*/
          |  val encoder/*L4*/ = User/*User.scala:2*/.encodeUser/*User.scala:2*/
          |}
          |""".stripMargin
      )
    } yield ()
  }

  test("fallback-to-presentation-compiler") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  val name = "John"
          |  // println(name)
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didChange("a/src/main/scala/a/Main.scala")(
        _.replaceAllLiterally("// ", "")
      )
      _ = assertNoDiff(
        server.workspaceDefinitions,
        // assert that definition of `name` and `assert` resolve even if they have not been saved.
        """|/a/src/main/scala/a/Main.scala
           |package a
           |object Main/*L1*/ {
           |  val name/*L2*/ = "John"
           |  println/*Predef.scala*/(name/*L2*/)
           |}
           |""".stripMargin
      )
    } yield ()
  }

}
