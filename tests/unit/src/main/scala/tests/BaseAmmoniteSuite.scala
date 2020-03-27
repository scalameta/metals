package tests

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._

abstract class BaseAmmoniteSuite(scalaVersion: String)
    extends BaseLspSuite("ammonite") {

  override def munitIgnore: Boolean =
    !isValidScalaVersionForEnv(scalaVersion)

  test("simple script") {
    // single script with import $ivy-s
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.as@@Json.noSpaces",
        ".metals/readonly/io/circe/syntax/package.scala"
      )

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.asJson.no@@Spaces",
        ".metals/readonly/io/circe/Json.scala"
      )

      // via presentation compiler, using the Ammonite build target classpath
      _ <- assertDefinitionAtLocation(
        ".metals/readonly/io/circe/Json.scala",
        "final def noSpaces: String = Printer.no@@Spaces.print(this)",
        ".metals/readonly/io/circe/Printer.scala"
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "decode[F@@oo](json)",
        "main.sc",
        6
      )

    } yield ()
  }

  test("hover") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      expectedHoverRes = """```scala
                           |val foo: Foo
                           |```
                           |```range
                           |val foo: Foo = Qux(13, Some(14.0))
                           |```""".stripMargin
      hoverRes <- assertHoverAtPos("main.sc", 10, 5)
      _ = assertNoDiff(hoverRes, expectedHoverRes)

    } yield ()
  }

  test("completion") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      expectedCompletionList = """noSpaces: String
                                 |noSpacesSortKeys: String""".stripMargin
      completionList <- server.completion("main.sc", "noSpaces@@")
      _ = assertNoDiff(completionList, expectedCompletionList)

    } yield ()
  }

  test("simple errored script") {
    val expectedDiagnostics =
      """main.sc:15:25: error: not found: type Fooz
        |val decodedFoo = decode[Fooz](json)
        |                        ^^^^
        |""".stripMargin
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Fooz](json)
           |
           |// no val, column of diagnostics must be correct too
           |decode[Foozz](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      diagnostics = server.client.pathDiagnostics("main.sc")
      _ = assertNoDiff(diagnostics, expectedDiagnostics)

    } yield ()
  }

  test("multi script") {
    // multiple scripts with mixed import $file-s and $ivy-s
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/lib1.sc
           |trait HasFoo {
           |  def foo: String = "foo"
           |}
           |
           |/lib2.sc
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import $$file.lib1, lib1.HasFoo
           |
           |trait HasReallyFoo extends HasFoo
           |
           |/main.sc
           | // scala $scalaVersion
           |import $$file.lib2, lib2.HasReallyFoo
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo extends HasReallyFoo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.as@@Json.noSpaces",
        ".metals/readonly/io/circe/syntax/package.scala"
      )

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.asJson.no@@Spaces",
        ".metals/readonly/io/circe/Json.scala"
      )

      // via presentation compiler, using the Ammonite build target classpath
      _ <- assertDefinitionAtLocation(
        ".metals/readonly/io/circe/Json.scala",
        "final def noSpaces: String = Printer.no@@Spaces.print(this)",
        ".metals/readonly/io/circe/Printer.scala"
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "decode[F@@oo](json)",
        "main.sc",
        4
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "sealed trait Foo extends Has@@ReallyFoo",
        "lib2.sc"
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "lib2.sc",
        "trait HasReallyFoo extends Has@@Foo",
        "lib1.sc"
      )

    } yield ()
  }

  test("ignore build.sc") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/project/build.properties
           |sbt.version=1.3.8
           |/build.sbt
           | // dummy sbt project, metals assumes a mill project else, and mill import seems flaky
           |lazy val a = project
           |  .settings(
           |    scalaVersion := "$scalaVersion"
           |  )
           |/main.sc
           | // scala $scalaVersion
           |val n = 2
           |/build.sc
           |import mill._, scalalib._, publish._
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.server.ammonite.maybeImport(server.toPath("main.sc"))

      messagesForScript = {
        val msgs = server.client.messageRequests.asScala.toVector
        server.client.messageRequests.clear()
        msgs
      }
      _ = assert(
        messagesForScript.contains(Messages.ImportAmmoniteScript.message)
      )

      _ <- server.didOpen("build.sc")
      _ <- server.server.ammonite.maybeImport(server.toPath("build.sc"))

      messagesForBuildSc = server.client.messageRequests.asScala.toVector
      _ = assert(
        !messagesForBuildSc.contains(Messages.ImportAmmoniteScript.message)
      )

    } yield ()
  }

  private def assertDefinitionAtLocation(
      file: String,
      definitionAt: String,
      expectedLocation: String,
      expectedLine: java.lang.Integer = null
  ): Future[Unit] = {

    val pos = {
      val content =
        new String(server.toPath(file).readAllBytes, StandardCharsets.UTF_8)
      val value = definitionAt.replaceAllLiterally("@@", "")
      val idx = content.onlyIndexOf(value).getOrElse {
        throw new Exception(
          s"Found multiple occurrences of '$value' in '$content'"
        )
      }
      val pipeCount = definitionAt.split("@@", -1).length - 1
      assert(pipeCount <= 1, s"Found several '|' characters in '$definitionAt'")
      val pipeIdx = Some(definitionAt.indexOf("@@"))
        .filter(_ >= 0)
        .getOrElse(0)
      content.indexToLspPosition(idx + pipeIdx)
    }

    server.server
      .definition(
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(server.toPath(file).toNIO.toUri.toString),
          pos
        )
      )
      .asScala
      .map { locations =>
        val locations0 = locations.asScala
        assert(
          locations0.length == 1,
          s"Expected a single location ($expectedLocation, ${Option(expectedLine)}), got ${locations0.length} ($locations0)"
        )
        val locationUri = new URI(locations0.head.getUri)
        assert(
          locationUri.getScheme == "file",
          s"Expected file location, got URI $locationUri"
        )
        val locationPath = workspace.toNIO.relativize(Paths.get(locationUri))
        val alternativeExpectedLocation =
          if (isWindows) Some(expectedLocation.replace('/', '\\'))
          else None
        assert(
          locationPath.toString == expectedLocation || alternativeExpectedLocation
            .exists(_ == locationPath.toString),
          s"Expected location $expectedLocation${alternativeExpectedLocation
            .fold("")(loc => s"(or $loc)")}, got $locationPath"
        )
        for (expectedLine0 <- Option(expectedLine)) {
          val line = locations0.head.getRange.getStart.getLine
          assert(
            line == expectedLine0,
            s"Expected line $expectedLine0, got $line"
          )
        }
        ()
      }
  }

  private def assertHoverAtPos(
      path: String,
      line: Int,
      char: Int
  ): Future[String] =
    server.server
      .hover(
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath("main.sc").toNIO.toUri.toASCIIString
          ),
          new Position(line, char)
        )
      )
      .asScala
      .map { res =>
        val code = server.textContents(path)
        TestHovers.renderAsString(code, Option(res), includeRange = true)
      }
}
