package tests

import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.URIEncoderDecoder

class HoverLspSuite extends BaseLspSuite("hover-") with TestHovers {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      TestingServer.TestDefault.copy(
        commandInHtmlFormat = Some(CommandHTMLFormat.VSCode)
      )
    )

  test("basic".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`:
           |""".stripMargin.hover,
      )
    } yield ()
  }

  // Command links need to be encoded normally, but that is not great for readability in tests
  private def decodeLinks(hover: String) =
    // not everything will be decoded by URIEncoderDecoder since it's not needed
    URIEncoderDecoder
      .decode(hover)
      .replace("%2F", "/")
      .replace("%28", "(")
      .replace("%29", ")")
      .replace("%23", "#")

  test("links") {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  val str : Str@@ing = ""
          |}""".stripMargin,
        """|```scala
           |type String: String
           |```
           |The `String` type in Scala has all the methods of the underlying
           | [java.lang.String](command:metals.goto?["java/lang/String."]), of which it is just an alias.
           |
           | In addition, extension methods in [scala.collection.StringOps](command:metals.goto?["scala/collection/StringOps."])
           | are added implicitly through the conversion [augmentString](command:metals.goto?["scala/Predef.augmentString()."]).
           |""".stripMargin.hover,
        modifyHover = decodeLinks,
      )
    } yield ()
  }

  test("links-java") {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |import java.net.URI
          |import java.nio.file.Paths
          |
          |object Main {
          |  val uri : URI = ???
          |  Paths.g@@et(uri)
          |}""".stripMargin,
        """|```scala
           |def get(uri: URI): Path
           |```
           |Converts the given URI to a [Path](command:metals.goto?["java/nio/file/Path#"]) object.
           |""".stripMargin.hover,
        modifyHover = decodeLinks,
      )
    } yield ()
  }

  test("basic-rambo".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/Main.scala
            |object Main extends App {
            |  // @@
            |}
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`:
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s) // index docs
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("update-docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s)
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover,
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s =>
        s.replace("test", "test2")
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test2
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("dependencies".tag(FlakyWindows), withoutVirtualDocs = true) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  println(42)
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ =
        server.workspaceDefinitions // triggers goto definition, creating Predef.scala
      _ <- server.assertHover(
        "scala/Predef.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`:
           |""".stripMargin.hover,
        root = workspace.resolve(Directories.readonly),
      )
    } yield ()
  }

}
