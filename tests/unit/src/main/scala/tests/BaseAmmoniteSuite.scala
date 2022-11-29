package tests

import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.lsp4j.MessageActionItem

abstract class BaseAmmoniteSuite(scalaVersion: String)
    extends BaseLspSuite(s"ammonite-$scalaVersion")
    with ScriptsAssertions {

  override def munitIgnore: Boolean =
    !isValidScalaVersionForEnv(scalaVersion)

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    server.client.showMessageRequestHandler = { params =>
      if (params == Messages.ImportScalaScript.params())
        Some(new MessageActionItem(Messages.ImportScalaScript.dismiss))
      else
        None
    }

  }

  test("simple-script") {
    // single script with import $ivy-s
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.14.2`
           |import $$ivy.`io.circe::circe-generic:0.14.2`
           |import $$ivy.`io.circe::circe-parser:0.14.2`
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
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.as@@Json.noSpaces",
        "io/circe/syntax/package.scala",
      )

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.asJson.no@@Spaces",
        "io/circe/Json.scala",
      )

      // via presentation compiler, using the Ammonite build target classpath
      _ <- assertDefinitionAtLocation(
        "io/circe/Json.scala",
        "final def noSpaces: String = Printer.no@@Spaces.print(this)",
        "io/circe/Printer.scala",
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "decode[F@@oo](json)",
        "main.sc",
        6,
      )

    } yield ()
  }

  test("multi-stage") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "scalaVersion": "$scalaVersion"
            |  }
            |}
            |/main.sc
            |
            |interp.repositories() ++= Seq(coursierapi.MavenRepository.of("https://jitpack.io"))
            |
            |@
            |
            |import $$ivy.`io.circe::circe-json-schema:0.1.0`
            |
            |import io.circe.schema.Schema
            |
            |val schema = Schema.loadFromString("{}")
            |println(schema.isSuccess)
            |
            |/build.sc
            |
            |// this part may contain some config
            |@
            |import mill._
            |import mill.scalalib._
            |object demo extends ScalaModule {
            |  def scalaVersion: T[String] = T("2.13.10")
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
      _ <- server.didSave("main.sc")(identity)
      _ = assertNoDiagnostics()
      _ <- server.didOpen("build.sc")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("invalid-version") {
    cleanWorkspace()
    val fakeScalaVersion = "30.3.4"
    server.client.showMessageRequestHandler = { params =>
      if (params == Messages.ImportScalaScript.params())
        Some(new MessageActionItem(Messages.ImportScalaScript.doImportAmmonite))
      else if (params == Messages.ImportAllScripts.params())
        Some(new MessageActionItem(Messages.ImportAllScripts.importAll))
      else
        None
    }
    for {
      _ <- initialize(
        s"""|/main.sc
            | // scala ${fakeScalaVersion}
            |
            |val cantStandTheHeat = "stay off the street"
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("main.sc")
      _ = assertEquals(
        server.client.workspaceShowMessages,
        s"Error importing Scala script ${workspace.resolve("main.sc")}. See the logs for more details.",
      )
      _ <- server.didSave("main.sc") { text =>
        text.replace(fakeScalaVersion, scalaVersion)
      }
      _ <- server.server.indexingPromise.future
      targets <- server.executeCommand(ServerCommands.ListBuildTargets)
      _ = assertEquals(
        targets.toString(),
        "[main.sc]",
      )
    } yield ()
  }

  // https://github.com/scalameta/metals/issues/1801
  test("hover".flaky) {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.14.2`
           |import $$ivy.`io.circe::circe-generic:0.14.2`
           |import $$ivy.`io.circe::circe-parser:0.14.2`
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
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      expectedHoverRes = """```scala
                           |val foo: Foo
                           |```
                           |```range
                           |val foo: Foo = Qux(13, Some(14.0))
                           |```""".stripMargin
      hoverRes <- assertHoverAtPos("main.sc", 10, 5)
      _ = assertNoDiff(hoverRes, expectedHoverRes)

      refreshedPromise = {
        val promise = Promise[Unit]()
        server.client.refreshModelHandler = { refreshCount =>
          if (refreshCount > 0 && !promise.isCompleted)
            promise.success(())
        }
        promise
      }
      _ <- server.didSave("main.sc") { _ =>
        s""" // scala $scalaVersion
           |import $$ivy.`com.github.alexarchambault::case-app:2.0.0-M16`
           |import caseapp.CaseApp
           |""".stripMargin
      }
      // wait for Ammonite build targets to be reloaded
      _ <- refreshedPromise.future
      _ <- server.didSave("main.sc") { text => text + "\nval a = 1" }
      // Hover on class defined in dependency loaded after the re-index.
      // Fails if interactive compilers were not properly discarded prior
      // to re-indexing.
      expectedNewHoverRes = """```scala
                              |val CaseApp: caseapp.core.app.CaseApp.type
                              |```""".stripMargin
      newHoverRes <- assertHoverAtPos("main.sc", 2, 18)
      _ = assertNoDiff(newHoverRes, expectedNewHoverRes)

    } yield ()
  }

  test("file-completion") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/b/otherMain.sc
           | // scala $scalaVersion
           |import $$file.other
           |
           |/b/otherScript.sc
           | // scala $scalaVersion
           |val a = ""
           |           
           |/b/other.sc
           | // scala $scalaVersion
           |val a = ""
           |
           |/b/others/Script.sc
           | // scala $scalaVersion
           |val a = ""
           |
           |/b/notThis.sc
           | // scala $scalaVersion
           |val a = ""
           |
           |""".stripMargin
      )
      _ <- server.didOpen("b/otherMain.sc")
      _ <- server.didOpen("b/other.sc")
      _ <- server.didOpen("b/otherScript.sc")
      _ <- server.didOpen("b/others/Script.sc")
      _ <- server.didOpen("b/notThis.sc")
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
      _ <- server.didSave("b/otherMain.sc")(identity)
      _ <- server.didSave("b/other.sc")(identity)
      _ <- server.didSave("b/otherScript.sc")(identity)
      _ <- server.didSave("b/others/Script.sc")(identity)
      _ <- server.didSave("b/notThis.sc")(identity)
      expectedCompletionList = """|other.sc
                                  |otherScript.sc
                                  |others""".stripMargin
      completionList <- server.completion(
        "b/otherMain.sc",
        "import $file.other@@",
      )
      _ = assertNoDiff(completionList, expectedCompletionList)

    } yield ()
  }

  test("file-completion-path") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/foos/Script.sc
           | // scala $scalaVersion
           |val a = ""
           |
           |/foo.sc
           | // scala $scalaVersion
           |import $$file.foos.Script
           |""".stripMargin
      )
      _ <- server.didOpen("foo.sc")
      _ <- server.didOpen("foos/Script.sc")
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
      _ <- server.didSave("foo.sc")(identity)
      _ <- server.didSave("foos/Script.sc")(identity)

      expectedCompletionList = "Script.sc"
      completionList <- server.completion(
        "foo.sc",
        "import $file.foos.Script@@",
      )
      _ = assertNoDiff(completionList, expectedCompletionList)

    } yield ()
  }

  test("completion") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.14.2`
           |import $$ivy.`io.circe::circe-generic:0.14.2`
           |import $$ivy.`io.circe::circe-parser:0.14.2`
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
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      expectedCompletionList = """noSpaces: String
                                 |noSpacesSortKeys: String""".stripMargin
      completionList <- server.completion("main.sc", "noSpaces@@")
      _ = assertNoDiff(completionList, expectedCompletionList)

    } yield ()
  }

  test("completion-class") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/Other.sc
           |val name = "Bob"
           |
           |/main.sc
           | // scala $scalaVersion
           |import $$file.Other
           |case class Test(aaa: Int, b: String)
           |val test = Test(1, "one")
           |test.aaa
           |Other.name
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
      completionList <- server.completion("main.sc", "test.a@@")
      _ = assert(completionList.startsWith("aaa: Int\n"))
      completionList <- server.completion("main.sc", "Other.name@@")
      _ = assertNoDiff(completionList, "name: String")

    } yield ()
  }

  test("simple errored script") {
    val expectedDiagnostics =
      if (scalaVersion.startsWith("2"))
        """errored.sc:15:25: error: not found: type Fooz
          |val decodedFoo = decode[Fooz](json)
          |                        ^^^^
          |errored.sc:18:8: error: not found: type Foozz
          |decode[Foozz](json)
          |       ^^^^^
          |""".stripMargin
      else
        """errored.sc:15:25: error: Not found: type Fooz
          |val decodedFoo = decode[Fooz](json)
          |                        ^^^^
          |errored.sc:18:8: error: Not found: type Foozz
          |decode[Foozz](json)
          |       ^^^^^
          |""".stripMargin

    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/errored.sc
           | // scala $scalaVersion
           |import $$ivy.`io.circe::circe-core:0.14.2`
           |import $$ivy.`io.circe::circe-generic:0.14.2`
           |import $$ivy.`io.circe::circe-parser:0.14.2`
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
      _ <- server.didOpen("errored.sc")
      _ <- server.didSave("errored.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      diagnostics = server.client.pathDiagnostics("errored.sc")
      _ = assertNoDiff(diagnostics, expectedDiagnostics)

    } yield ()
  }

  test("multi script") {
    // multiple scripts with mixed import $file-s and $ivy-s
    for {
      _ <- initialize(
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
           |import $$ivy.`io.circe::circe-core:0.14.2`
           |import $$ivy.`io.circe::circe-generic:0.14.2`
           |import $$ivy.`io.circe::circe-parser:0.14.2`
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
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.as@@Json.noSpaces",
        "io/circe/syntax/package.scala",
      )

      // via Ammonite-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "foo.asJson.no@@Spaces",
        "io/circe/Json.scala",
      )

      // via presentation compiler, using the Ammonite build target classpath
      _ <- assertDefinitionAtLocation(
        "io/circe/Json.scala",
        "final def noSpaces: String = Printer.no@@Spaces.print(this)",
        "io/circe/Printer.scala",
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "decode[F@@oo](json)",
        "main.sc",
        4,
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "main.sc",
        "sealed trait Foo extends Has@@ReallyFoo",
        "lib2.sc",
      )

      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- assertDefinitionAtLocation(
        "lib2.sc",
        "trait HasReallyFoo extends Has@@Foo",
        "lib1.sc",
      )

    } yield ()
  }

  test("ignore build.sc") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/project/build.properties
           |sbt.version=${V.sbtVersion}
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
      _ <- server.server
        .maybeImportScript(server.toPath("main.sc"))
        .getOrElse(Future.unit)

      messagesForScript = {
        val msgs = server.client.messageRequests.asScala.toVector
        server.client.messageRequests.clear()
        msgs
      }
      _ = assert(
        messagesForScript.contains(Messages.ImportScalaScript.message)
      )

      _ <- server.didOpen("build.sc")
      _ <- server.server
        .maybeImportScript(server.toPath("build.sc"))
        .getOrElse(Future.unit)

      messagesForBuildSc = server.client.messageRequests.asScala.toVector
      _ = assert(
        !messagesForBuildSc.contains(Messages.ImportScalaScript.message)
      )

    } yield ()
  }

  test("ivy-completion") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           |import $$ivy.`io.cir`
           |import $$ivy.`io.circe::circe-ref`
           |import $$ivy.`com.lihaoyi::upickle:1.4`
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      groupExpectedCompletionList = "io.circe"
      groupCompletionList <- server.completion(
        "main.sc",
        "import $ivy.`io.cir@@`",
      )
      _ = assertNoDiff(groupCompletionList, groupExpectedCompletionList)
      artefactCompletionList <- server.completion(
        "main.sc",
        "import $ivy.`io.circe::circe-ref@@`",
      )
      artefactExpectedCompletionList = getExpected(
        """|circe-refined
           |circe-refined_native0.4
           |circe-refined_sjs0.6
           |circe-refined_sjs1
           |""".stripMargin,
        Map(
          "3" -> """|circe-refined
                    |circe-refined_native0.4
                    |circe-refined_sjs1
                    |""".stripMargin
        ),
        scalaVersion,
      )
      _ = assertNoDiff(artefactCompletionList, artefactExpectedCompletionList)

      versionExpectedCompletionList =
        List("1.4.4", "1.4.3", "1.4.2", "1.4.1", "1.4.0")
      response <- server.completionList(
        "main.sc",
        "import $ivy.`com.lihaoyi::upickle:1.4@@`",
      )
      versionCompletionList = response
        .getItems()
        .asScala
        .map(_.getLabel())
        .toList
      _ = assertEquals(versionCompletionList, versionExpectedCompletionList)
      noCompletions <- server.completion(
        "main.sc",
        "import $ivy.`com.lihaoyi::upickle:1.4`@@",
      )
      _ = assertNoDiff(noCompletions, "")
    } yield ()
  }
}
