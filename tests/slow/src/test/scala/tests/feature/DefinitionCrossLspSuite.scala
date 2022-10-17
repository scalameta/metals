package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.InitializationOptions

import munit.Location
import org.eclipse.{lsp4j => l}
import tests.BaseCompletionLspSuite
import tests.ScriptsAssertions

class DefinitionCrossLspSuite
    extends BaseCompletionLspSuite("definition-cross")
    with ScriptsAssertions {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        statusBarProvider = Some("show-message")
      )
    )

  if (super.isValidScalaVersionForEnv(BuildInfo.scala211)) {
    test("2.11") {
      basicDefinitionTest(BuildInfo.scala211)
    }
  }

  if (super.isValidScalaVersionForEnv(BuildInfo.scala212)) {
    test("2.12") {
      basicDefinitionTest(BuildInfo.scala212)
    }
  }

  test("underscore") {
    cleanDatabase()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${BuildInfo.scala213}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Main {
           |  val tests = new Test
           |  tests.dummy()
           |}
           |/a/src/main/scala/a/Test.scala
           |package a
           |
           |class Test{
           |  val x = 100_000
           |  def dummy() = x
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("a/src/main/scala/a/Test.scala")
      _ = assertNoDiagnostics()
      _ = server.assertReferenceDefinitionBijection()
    } yield ()
  }

  test("inline") {
    cleanDatabase()
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${BuildInfo.scala3}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  Foo("").setBaz1
           |  Foo("").setBaz2
           |}
           |
           |/a/src/main/scala/a/Foo.scala
           |package a
           |
           |case class Foo(value: String)
           |extension (x: Foo) {
           |  inline def setBaz1: Unit = ()
           |  def setBaz2: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      expectedLocation = new l.Location(
        workspace.resolve("a/src/main/scala/a/Foo.scala").toURI.toString(),
        new l.Range(new l.Position(5, 6), new l.Position(5, 13)),
      )
      _ = assertNoDiagnostics()
      _ <- definitionsAt(
        "a/src/main/scala/a/Main.scala",
        "  Foo(\"\").setBa@@z2",
      ).map {
        case List(loc) =>
          assertEquals(loc, expectedLocation)
        case _ => fail("expected single location")
      }
    } yield ()
  }

  test("scala3-stdLibPatches") {
    def assertDefinitionUri(
        file: String,
        query: String,
        assertUri: String => Boolean,
        clue: String,
    )(implicit l: Location): Future[Unit] = {
      definitionsAt(file, query).map { locs =>
        assert(locs.size >= 1, s"Expected at least one location")
        val uri = locs.head.getUri()
        assert(assertUri(uri), s"$clue // uri: $uri")
      }
    }
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${BuildInfo.scala3}"
           |  },
           |  "b": {
           |    "scalaVersion": "${BuildInfo.scala213}"
           |  }
           |
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |class A {
           |  // assert if patched in scala.runtime.stdLibPatches.Predef
           |  assert(false)
           |  // ??? is defined in scala.Predef
           |  val x = ???
           |}
           |/b/src/main/scala/b/B.scala
           |package b
           |
           |class B {
           |  assert(false)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- assertDefinitionUri(
        "a/src/main/scala/a/A.scala",
        "asse@@rt(false)",
        uri => uri.contains("scala3-library_3"),
        "Expected scala3 library location",
      )
      _ <- assertDefinitionUri(
        "a/src/main/scala/a/A.scala",
        "val x = ??@@?",
        uri => uri.contains("scala-library"),
        "Expected scala2 library location",
      )
      _ <- assertDefinitionUri(
        "b/src/main/scala/b/B.scala",
        "asse@@rt(false)",
        uri => uri.contains("scala-library"),
        "Expected scala2 library location",
      )
    } yield ()
  }

  def basicDefinitionTest(scalaVersion: String): Future[Unit] = {
    cleanDatabase()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${scalaVersion}"
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
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  prin@@tln("hello!")
          |}""".stripMargin,
        if (scalaVersion.startsWith("2.12"))
          """|```scala
             |def println(x: Any): Unit
             |```
             |Prints out an object to the default output, followed by a newline character.
             |
             |
             |**Parameters**
             |- `x`: the object to print.
             |""".stripMargin
        else """|```scala
               |def println(x: Any): Unit
               |```
               |""".stripMargin,
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }
}
