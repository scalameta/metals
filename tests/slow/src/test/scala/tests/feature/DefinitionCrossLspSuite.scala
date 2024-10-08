package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsServerConfig

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

  override def serverConfig: MetalsServerConfig = super.serverConfig.copy(
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

  test("i5494") {
    cleanWorkspace()
    val mainFile =
      """|package a
         |
         |def otherMethod(prd: TypeDecl.Pro@@duct) = ???
         |def mm(prd: Types.Product) = ???
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": { "scalaVersion": "${BuildInfo.scala3}" }
            |}
            |
            |/a/src/main/scala/a/TypeDecl.scala
            |package a
            |
            |enum TypeDecl:
            |  case Product(id: String)
            |  case Coproduct(id: String)
            |
            |/a/src/main/scala/a/Types.scala
            |package a
            |
            |sealed trait Types {}
            |
            |object Types {
            |  case class Product(id: String) extends Types
            |}
            |
            |/a/src/main/scala/a/Main.scala
            |${mainFile.replace("@@", "")}
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = server.didOpen("a/src/main/scala/a/TypeDecl.scala")
      _ = server.didOpen("a/src/main/scala/a/Types.scala")
      _ = server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions
      definition <- server.definition(
        "a/src/main/scala/a/Main.scala",
        mainFile,
        workspace,
      )
      uri = workspace
        .resolve("a/src/main/scala/a/TypeDecl.scala")
        .toURI
        .toString()
      _ = assertEquals(
        definition,
        List(
          new l.Location(
            uri,
            new l.Range(new l.Position(3, 7), new l.Position(3, 14)),
          )
        ),
      )
    } yield ()
  }

  test("scaladoc-definition-enum") {
    val testCase =
      """|package a
         |
         |enum O:
         | def e = 1
         | /**
         |  *  This is [[ this.`package.@@A` ]]
         |  */
         | case `package.A`
         |""".stripMargin
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { "scalaVersion": "${BuildInfo.scala3}" }
           |}
           |/a/src/main/scala/a/Main.scala
           |${testCase.replace("@@", "")}
           |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      locations <- server.definition(
        "a/src/main/scala/a/Main.scala",
        testCase,
        workspace,
      )
      _ = assert(locations.nonEmpty)
      _ = assert(locations.head.getUri().endsWith("a/Main.scala"))
    } yield ()
  }

  for (version <- Seq(BuildInfo.scala213, BuildInfo.scala3)) {
    test(s"fallback-to-workspace-search-$version") {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": { "scalaVersion" : "${version}"},
             |  "b": { "scalaVersion" : "${version}", "dependsOn": [ "a" ] }
             |
             |}
             |/a/src/main/scala/a/Main.scala
             |package a
             |
             |class Main
             |object Main {
             |  // Error that makes the whole target not compile
             |  val name: Int = "John"
             |  case class Bar()
             |  val ver: Version = null
             |}
             |
             |class Version
             |
             |/b/src/main/scala/b/Foo.scala
             |package b
             |import a.Main
             |import a.Main.Bar
             |
             |object Foo{
             |  val ver: Version = null
             |  val nm = Main.name
             |  val foo = Bar()
             |  val m: Main = new Main()
             |}
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.didOpen("b/src/main/scala/b/Foo.scala")
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          if (version == BuildInfo.scala3)
            """|a/src/main/scala/a/Main.scala:6:19: error: Found:    ("John" : String)
               |Required: Int
               |  val name: Int = "John"
               |                  ^^^^^^
               |""".stripMargin
          else
            """|a/src/main/scala/a/Main.scala:6:19: error: type mismatch;
               | found   : String("John")
               | required: Int
               |  val name: Int = "John"
               |                  ^^^^^^
               |""".stripMargin,
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          """|/a/src/main/scala/a/Main.scala
             |package a
             |
             |class Main/*L2*/
             |object Main/*L3*/ {
             |  // Error that makes the whole target not compile
             |  val name/*L5*/: Int/*Int.scala*/ = "John"
             |  case class Bar/*L6*/()
             |  val ver/*L7*/: Version/*L10*/ = null
             |}
             |
             |class Version/*L10*/
             |
             |/b/src/main/scala/b/Foo.scala
             |package b
             |import a/*<no symbol>*/.Main/*;Main.scala:2;Main.scala:3*/
             |import a/*<no symbol>*/.Main/*Main.scala:3*/.Bar/*Main.scala:6*/
             |
             |object Foo/*L4*/{
             |  val ver/*L5*/: Version/*<no symbol>*/ = null
             |  val nm/*L6*/ = Main/*Main.scala:3*/.name/*Main.scala:5*/
             |  val foo/*L7*/ = Bar/*Main.scala:6*/()
             |  val m/*L8*/: Main/*Main.scala:2*/ = new Main/*Main.scala:2*/()
             |}
             |""".stripMargin,
        )
      } yield ()
    }

    test(s"fallback-to-workspace-search-shadowing-$version") {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": { "scalaVersion" : "${version}" },
             |  "b": { "scalaVersion" : "${version}", "dependsOn": [ "a" ] }
             |
             |}
             |/a/src/main/scala/a/Main.scala
             |package a
             |
             |object Main {
             |  val `na-me`: Int = "John"
             |  object Other
             |}
             |
             |object Main2 {
             |  object Other
             |}
             |
             |
             |/b/src/main/scala/b/Foo.scala
             |package b
             |
             |object Foo {
             |  import a.Main.Other
             |  val other = Other
             |  a.Main.`na-me`
             |}
             |object Foo2 {
             |  import a.Main2.Other
             |  val other = Other
             |}
             |object Foo3 {
             |  val Foo = ""
             |  Foo
             |}
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.didOpen("b/src/main/scala/b/Foo.scala")
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          if (version == BuildInfo.scala3)
            """|a/src/main/scala/a/Main.scala:4:22: error: Found:    ("John" : String)
               |Required: Int
               |  val `na-me`: Int = "John"
               |                     ^^^^^^
               |""".stripMargin
          else
            """|a/src/main/scala/a/Main.scala:4:22: error: type mismatch;
               | found   : String("John")
               | required: Int
               |  val `na-me`: Int = "John"
               |                     ^^^^^^
               |""".stripMargin,
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          """|/a/src/main/scala/a/Main.scala
             |package a
             |
             |object Main/*L2*/ {
             |  val `na-me`/*L3*/: Int/*Int.scala*/ = "John"
             |  object Other/*L4*/
             |}
             |
             |object Main2/*L7*/ {
             |  object Other/*L8*/
             |}
             |
             |
             |/b/src/main/scala/b/Foo.scala
             |package b
             |
             |object Foo/*L2*/ {
             |  import a/*<no symbol>*/.Main/*Main.scala:2*/.Other/*Main.scala:4*/
             |  val other/*L4*/ = Other/*Main.scala:4*/
             |  a/*<no symbol>*/.Main/*Main.scala:2*/.`na-me`/*Main.scala:3*/
             |}
             |object Foo2/*L7*/ {
             |  import a/*<no symbol>*/.Main2/*Main.scala:7*/.Other/*Main.scala:8*/
             |  val other/*L9*/ = Other/*Main.scala:8*/
             |}
             |object Foo3/*L11*/ {
             |  val Foo/*L12*/ = ""
             |  Foo/*L12*/
             |}
             |""".stripMargin,
        )
      } yield ()
    }

    test(s"fallback-to-workspace-search-packages-$version") {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": { "scalaVersion" : "${version}" }
             |}
             |/a/src/main/scala/a/Main.scala
             |package a
             |
             |object Main {
             |  val name: Int = "John"
             |}
             |
             |/a/src/main/scala/a/b/Foo.scala
             |package a
             |package b
             |
             |object Foo {
             |  Main.name
             |}
             |
             |/a/src/main/scala/a/b/Bar.scala
             |package a.b
             |
             |object Bar {
             |  Main.name
             |}
             |
             |/a/src/main/scala/a/b/c/OtherMain.scala
             |package a.b.c
             |
             |object Main {
             |  val name: Int = "Lemon"
             |}
             |
             |/a/src/main/scala/a/b/c/d/Baz.scala
             |package a
             |package b
             |package c
             |package d
             |
             |object Baz {
             |  Main.name
             |}
             |
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.didOpen("a/src/main/scala/a/b/Foo.scala")
        _ <- server.didOpen("a/src/main/scala/a/b/Bar.scala")
        _ <- server.didOpen("a/src/main/scala/a/b/c/OtherMain.scala")
        _ <- server.didOpen("a/src/main/scala/a/b/c/d/Baz.scala")
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          if (version == BuildInfo.scala3)
            """|a/src/main/scala/a/Main.scala:4:19: error: Found:    ("John" : String)
               |Required: Int
               |  val name: Int = "John"
               |                  ^^^^^^
               |a/src/main/scala/a/b/Bar.scala:4:3: error: Not found: Main - did you mean Math? or perhaps wait?
               |  Main.name
               |  ^^^^
               |a/src/main/scala/a/b/c/OtherMain.scala:4:19: error: Found:    ("Lemon" : String)
               |Required: Int
               |  val name: Int = "Lemon"
               |                  ^^^^^^^
               |""".stripMargin
          else
            """|a/src/main/scala/a/Main.scala:4:19: error: type mismatch;
               | found   : String("John")
               | required: Int
               |  val name: Int = "John"
               |                  ^^^^^^
               |a/src/main/scala/a/b/Bar.scala:4:3: error: not found: value Main
               |  Main.name
               |  ^^^^
               |a/src/main/scala/a/b/c/OtherMain.scala:4:19: error: type mismatch;
               | found   : String("Lemon")
               | required: Int
               |  val name: Int = "Lemon"
               |                  ^^^^^^^
               |""".stripMargin,
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          """|/a/src/main/scala/a/Main.scala
             |package a
             |
             |object Main/*L2*/ {
             |  val name/*L3*/: Int/*Int.scala*/ = "John"
             |}
             |
             |/a/src/main/scala/a/b/Bar.scala
             |package a.b
             |
             |object Bar/*L2*/ {
             |  Main/*<no symbol>*/.name/*<no symbol>*/
             |}
             |
             |/a/src/main/scala/a/b/Foo.scala
             |package a
             |package b
             |
             |object Foo/*L3*/ {
             |  Main/*Main.scala:2*/.name/*Main.scala:3*/
             |}
             |
             |/a/src/main/scala/a/b/c/OtherMain.scala
             |package a.b.c
             |
             |object Main/*L2*/ {
             |  val name/*L3*/: Int/*Int.scala*/ = "Lemon"
             |}
             |
             |/a/src/main/scala/a/b/c/d/Baz.scala
             |package a
             |package b
             |package c
             |package d
             |
             |object Baz/*L5*/ {
             |  Main/*OtherMain.scala:2*/.name/*OtherMain.scala:3*/
             |}
             |""".stripMargin,
        )
      } yield ()
    }

    test(s"fallback-object-imports-$version") {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": { "scalaVersion" : "${version}" }
             |}
             |/a/src/main/scala/a/Main.scala
             |package a
             |import b.Foo._
             |
             |object Main {
             |  val name: Version = ???
             |}
             |
             |/a/src/main/scala/a/b/Foo.scala
             |package a.b
             |
             |trait Foo
             |object Foo extends Foo{
             |  val a = 123
             |}
             |
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.didOpen("a/src/main/scala/a/b/Foo.scala")
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          if (version == BuildInfo.scala3)
            """|a/src/main/scala/a/Main.scala:5:13: error: Not found: type Version
               |  val name: Version = ???
               |            ^^^^^^^
               |""".stripMargin
          else
            """|a/src/main/scala/a/Main.scala:5:13: error: not found: type Version
               |  val name: Version = ???
               |            ^^^^^^^
               |""".stripMargin,
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          """|/a/src/main/scala/a/Main.scala
             |package a
             |import b/*<no symbol>*/.Foo/*Foo.scala:3*/._
             |
             |object Main/*L3*/ {
             |  val name/*L4*/: Version/*<no symbol>*/ = ???/*Predef.scala*/
             |}
             |
             |/a/src/main/scala/a/b/Foo.scala
             |package a.b
             |
             |trait Foo/*L2*/
             |object Foo/*L3*/ extends Foo/*L2*/{
             |  val a/*L4*/ = 123
             |}
             |""".stripMargin,
        )
      } yield ()
    }
  }
}
