package tests.inlayHints

import scala.concurrent.Future

import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.InlayHint
import tests.BaseLspSuite

class InlayHintsHoverSuite extends BaseLspSuite("implicits") {

  check(
    "local",
    """|object Main {
       |  def foo() = {
       |    implicit val imp: Int = 2
       |    def addOne(x: Int)(implicit one: Int) = x + one
       |    val x = addOne(1)<<(imp)>>
       |  }
       |}
       |""".stripMargin,
    """|```scala
       |implicit val imp: Int
       |```
       |""".stripMargin,
  )

  check(
    "type-param",
    """|
       |case class Foo[A](a: A)
       |object Main {
       |  def hello[T](t: T) = t
       |  val x = hello<<[Foo[Int]]>>(Foo(1))
       |}
       |""".stripMargin,
    """|```scala
       |case class Foo[A]: Foo
       |```
       |
       |```scala
       |final abstract class Int: Int
       |```
       |`Int`, a 32-bit signed integer (equivalent to Java's `int` primitive type) is a
       | subtype of [scala.AnyVal](scala.AnyVal). Instances of `Int` are not
       | represented by an object in the underlying runtime system.
       |
       | There is an implicit conversion from [scala.Int](scala.Int) => [scala.runtime.RichInt](scala.runtime.RichInt)
       | which provides useful non-primitive operations.
       |""".stripMargin,
  )

  check(
    "implicit-conversion",
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = new User(x.toString)
       |  val y: User = <<intToUser>>1
       |}
       |""".stripMargin,
    """|```scala
       |implicit def intToUser(x: Int): User
       |```
       |""".stripMargin,
  )

  check(
    "import-rename",
    """|import scala.collection.{AbstractMap => AB}
       |import scala.collection.{Set => S}
       |
       |object Main {
       |  def test(d: S[Int], f: S[Char]): AB[Int, String] = {
       |    val x = d.map(_.toString)
       |    val y = f
       |    ???
       |  }
       |  val x<<: AB[Int,String]>> = test(Set(1), Set('a'))
       |}
       |""".stripMargin,
    """|```scala
       |abstract class AbstractMap[K, V]: AbstractMap
       |```
       |Explicit instantiation of the `Map` trait to reduce class file size in subclasses.
       |
       |```scala
       |final abstract class Int: Int
       |```
       |`Int`, a 32-bit signed integer (equivalent to Java's `int` primitive type) is a
       | subtype of [scala.AnyVal](scala.AnyVal). Instances of `Int` are not
       | represented by an object in the underlying runtime system.
       |
       | There is an implicit conversion from [scala.Int](scala.Int) => [scala.runtime.RichInt](scala.runtime.RichInt)
       | which provides useful non-primitive operations.
       |
       |```scala
       |type String: String
       |```
       |The `String` type in Scala has all the methods of the underlying
       | [java.lang.String](java.lang.String), of which it is just an alias.
       |
       | In addition, extension methods in [scala.collection.StringOps](scala.collection.StringOps)
       | are added implicitly through the conversion [augmentString](augmentString).
       |""".stripMargin,
  )

  check(
    "import-rename2",
    """|object Main {
       |  import scala.collection.{Set => S}
       |  import Foo._
       |
       |  def test(d: S[Int])(implicit foo: S[Int]): Int = {
       |    val x = d.map(_.toString)
       |    ???
       |  }
       |
       |  val x = test(Set(1))<<(ttt)>>
       |}
       |
       |object Foo {
       |  implicit val ttt: Set[Int] = Set(1)
       |}
       |""".stripMargin,
    // TODO: We should show info about renames (`val ttt: S[Int]` instead of `val ttt: Set[Int]`)
    """|```scala
       |implicit val ttt: Set[Int]
       |```
       |""".stripMargin,
  )

  def check(
      name: TestOptions,
      fileContent: String,
      hoverMessage: String,
      dependencies: List[String] = Nil,
  )(implicit
      loc: Location
  ): Unit = {
    val config =
      """|{
         |  "show-implicit-arguments": true,
         |  "show-implicit-conversions-and-classes": true,
         |  "show-inferred-type": "true"
         |}
         |""".stripMargin
    val fileName = "Main.scala"
    val libraryDependencies =
      if (dependencies.isEmpty) ""
      else
        s""""libraryDependencies": [${dependencies.map(dep => s"\"$dep\"").mkString(",")}]"""
    val query = fileContent.substring(
      fileContent.indexOf("<<") + 2,
      fileContent.indexOf(">>"),
    )
    val code = fileContent
      .replaceAll(raw"<<(.*?)>>", "")
    test(name) {
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":{$libraryDependencies}}
             |/a/src/main/scala/a/$fileName
             |$code
        """.stripMargin
        )
        _ <- server.didOpen(s"a/src/main/scala/a/$fileName")
        _ <- server.didChangeConfiguration(config)
        hints <- server.inlayHints(
          s"a/src/main/scala/a/$fileName",
          code,
        )
        hint = findInlayHint(hints, query)
        _ = assert(hint.isDefined)
        _ <- assertHover(hint.get, hoverMessage)
      } yield ()
    }
  }

  private def findInlayHint(
      hints: List[InlayHint],
      query: String,
  ): Option[InlayHint] = {
    hints.find { hint =>
      val label = hint.getLabel().asScala match {
        case Left(label) => label
        case Right(parts) => parts.asScala.map(_.getValue()).mkString
      }
      label.contains(query)
    }
  }

  private def assertHover(
      inlayHint: InlayHint,
      expected: String,
  ): Future[Unit] = {
    inlayHint.setData(inlayHint.getData().toJson)
    server.fullServer.inlayHintResolve(inlayHint).asScala.map { resolved =>
      val tooltip = resolved.getLabel().asScala match {
        case Left(_) =>
          Option(resolved.getTooltip())
            .map(_.getRight().getValue())
            .getOrElse("")
        case Right(parts) =>
          parts.asScala
            .map(part =>
              Option(part.getTooltip())
                .map(_.getRight().getValue())
                .getOrElse("")
            )
            .mkString("\n")
      }
      assertNoDiff(tooltip, expected)
    }
  }
}
