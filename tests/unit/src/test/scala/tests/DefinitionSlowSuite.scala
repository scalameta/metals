package tests

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.Messages.Only212Navigation

object DefinitionSlowSuite extends BaseSlowSuite("definition") {

  override def testAsync(
      name: String,
      maxDuration: Duration = Duration("3min")
  )(run: => Future[Unit]): Unit = {
    if (isAppveyor) {
      // src.zip is missing on Appveyor which breaks definition tests.
      ignore(name) {}
    } else {
      super.testAsync(name, maxDuration)(run)
    }
  }

  testAsync("definition") {
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
        """|
           |/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future/*Future.java:95*/ // unused
           |import scala.util.Failure/*Try.scala:213*/ // unused
           |object Main/*L3*/ extends App/*App.scala:38*/ {
           |  val message/*L4*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java:56*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java:44*/())
           |  println/*Predef.scala:392*/(message/*L4*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future/*Future.java:95*/ // unused
           |import scala.util.Failure/*Try.scala:213*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala:1559*/
           |object MainSuite/*L4*/ extends FunSuite/*FunSuite.scala:1559*/ {
           |  test/*FunSuiteLike.scala:119*/("a") {
           |    val condition/*L6*/ = Main/*Main.scala:3*/.message/*Main.scala:4*/.contains/*String.java:2131*/("Hello")
           |    assert/*Assertions.scala:414*/(condition/*L6*/)
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
        """|
           |/a/src/main/scala/a/Main.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java:95*/ // unused
           |import scala.util.Failure/*Try.scala:213*/ // unused
           |object Main/*L5*/ extends App/*App.scala:38*/ {
           |  val helloMessage/*<no symbol>*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java:56*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java:44*/())
           |  println/*Predef.scala:392*/(message/*<no symbol>*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java:95*/ // unused
           |import scala.util.Failure/*Try.scala:213*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala:1559*/
           |object MainSuite/*L6*/ extends FunSuite/*FunSuite.scala:1559*/ {
           |  test/*FunSuiteLike.scala:119*/(testName/*<no symbol>*/) {
           |    val condition/*L8*/ = Main/*Main.scala:5*/.message/*<no symbol>*/.contains/*String.java:2131*/("Hello")
           |    assert/*Assertions.scala:414*/(condition/*L8*/)
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
  testAsync("missing-compiler-plugin") {
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
           |@typeclass/*<no symbol>*/ trait Contravariant/*L5*/[F/*L5*/[_]] extends Invariant/*Invariant.scala:7*/[F/*L5*/] { self/*L5*/ =>
           |  def contramap/*L6*/[A/*L6*/, B/*L6*/](fa/*L6*/: F/*L5*/[A/*L6*/])(f/*L6*/: B/*L6*/ => A/*L6*/): F/*L5*/[B/*L6*/]
           |  override def imap/*L7*/[A/*L7*/, B/*L7*/](fa/*L7*/: F/*L5*/[A/*L7*/])(f/*L7*/: A/*L7*/ => B/*L7*/)(fi/*L7*/: B/*L7*/ => A/*L7*/): F/*L5*/[B/*L7*/] = contramap/*L6*/(fa/*L7*/)(fi/*L7*/)
           |
           |  def compose/*L9*/[G/*L9*/[_]: Contravariant/*L5*/]: Functor/*Functor.scala:11*/[λ/*<no symbol>*/[α/*<no symbol>*/ => F/*<no symbol>*/[G/*<no symbol>*/[α/*<no symbol>*/]]]] =
           |    new ComposedContravariant/*Composed.scala:107*/[F/*L5*/, G/*L9*/] {
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
           |  override def composeFunctor/*L23*/[G/*L23*/[_]: Functor/*Functor.scala:11*/]: Contravariant/*L5*/[λ/*<no symbol>*/[α/*<no symbol>*/ => F/*<no symbol>*/[G/*<no symbol>*/[α/*<no symbol>*/]]]] =
           |    new ComposedContravariantCovariant/*Composed.scala:115*/[F/*L5*/, G/*L23*/] {
           |      val F/*L25*/ = self/*L5*/
           |      val G/*L26*/ = Functor/*Functor.scala:11*/[G/*L23*/]
           |    }
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |import cats._
           |import cats.implicits/*implicits.scala:2*/._
           |object Main/*L2*/ {
           |  println/*Predef.scala:392*/(Contravariant/*Contravariant.scala:5*/[Show/*Show.scala:9*/])
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

  testAsync("2.11") {
    cleanDatabase()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "scalaVersion": "2.11.12"
          |  }
          |}
          |/a/src/main/scala/a/Main.scala
          |object Main {
          |  println("hello!")
          |}
          |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions // trigger definition
      _ <- server.didOpen("scala/Predef.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Only212Navigation.params("2.11.12").getMessage
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  testAsync("stale") {
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
        """
          |/a/src/main/scala/a/Main.scala
          |object Main/*L0*/ {
          |  val x/*L1*/: Int/*Int.scala:21*/ = math.max/*package.scala:191*/(1, 2)
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
           |  val x/*L1*/: Int/*Int.scala:21*/ = math.max/*package.scala:191*/
           |}
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("annotations") {
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

}
