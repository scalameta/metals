package tests

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.scalacli.ScalaCli

import ch.epfl.scala.bsp4j.MessageType
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageActionItem

abstract class BaseScalaCliSuite(scalaVersion: String)
    extends BaseLspSuite(s"scala-cli-$scalaVersion")
    with ScriptsAssertions {

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  private def timeout(
      message: String,
      duration: FiniteDuration,
  ): Future[Unit] = {
    val p = Promise[Unit]()
    val r: Runnable = { () =>
      p.failure(new TimeoutException(message))
    }
    scheduler.schedule(r, duration.length, duration.unit)
    p.future
  }

  override def afterAll(): Unit = {
    super.afterAll()
    scheduler.shutdown()
  }

  override def munitIgnore: Boolean =
    !isValidScalaVersionForEnv(scalaVersion)

  private var importedPromise = Promise[Unit]()

  override def newServer(workspaceName: String): Unit = {
    super.newServer(workspaceName)
    val previousShowMessageHandler = server.client.showMessageHandler
    server.client.showMessageHandler = { params =>
      if (params == Messages.ImportScalaScript.ImportedScalaCli)
        importedPromise.success(())
      else if (
        params.getType == MessageType.ERROR && params.getMessage.startsWith(
          "Error importing Scala CLI project "
        )
      )
        importedPromise.failure(
          new Exception(s"Error importing project: $params")
        )
      else
        previousShowMessageHandler(params)
    }
    val previousShowMessageRequestHandler =
      server.client.showMessageRequestHandler
    server.client.showMessageRequestHandler = { params =>
      def useBsp = Files.exists(
        server.server.workspace.resolve(".bsp/scala-cli.json").toNIO
      )
      if (params == Messages.ImportScalaScript.params())
        Some(
          new MessageActionItem(
            if (useBsp)
              Messages.ImportScalaScript.dismiss
            else
              Messages.ImportScalaScript.doImportScalaCli
          )
        )
      else if (params == Messages.ImportAllScripts.params())
        Some(
          new MessageActionItem(
            if (useBsp)
              Messages.ImportAllScripts.dismiss
            else
              Messages.ImportAllScripts.importAll
          )
        )
      else
        previousShowMessageRequestHandler(params)
    }
  }

  private def manualLayout =
    s"""/metals.json
       |{
       |  "a": {
       |    "scalaVersion": "$scalaVersion"
       |  }
       |}
       |
       |""".stripMargin
  private def bspLayout =
    s"""/.bsp/scala-cli.json
       |${BaseScalaCliSuite.scalaCliBspJsonContent()}
       |
       |/.scala-build/ide-inputs.json
       |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
       |
       |""".stripMargin

  private def scalaCliInitialize(
      useBsp: Boolean
  )(layout: String): Future[InitializeResult] = {
    if (!useBsp)
      importedPromise = Promise[Unit]()
    initialize(
      (if (useBsp) bspLayout else manualLayout) + layout
    )
  }

  private def waitForImport(useBsp: Boolean): Future[Unit] =
    if (useBsp) Future.successful(())
    else
      Future
        .firstCompletedOf(
          List(
            importedPromise.future,
            timeout("import timeout", 180.seconds),
          )
        )

  for (useBsp <- Seq(true, false)) {
    val message = if (useBsp) "BSP" else "manual"
    test(s"simple file $message") {
      simpleFileTest(useBsp)
    }
    test(s"simple script $message") {
      simpleScriptTest(useBsp)
    }
  }

  private val simpleFileLayout =
    s"""|/MyTests.scala
        |//> using scala "$scalaVersion"
        |//> using lib "com.lihaoyi::utest::0.7.9"
        |//> using lib "com.lihaoyi::pprint::0.6.4"
        |
        |import foo.Foo
        |import utest._
        |
        |object MyTests extends TestSuite {
        |  pprint.log(2)
        |  val tests = Tests {
        |    test("foo") {
        |      assert(2 + 2 == 4)
        |    }
        |    test("nope") {
        |      assert(2 + 2 == (new Foo).value)
        |    }
        |  }
        |}
        |
        |/foo.sc
        |class Foo {
        |  def value = 5
        |}
        |""".stripMargin

  test("connecting-scalacli".flaky) {
    cleanWorkspace()
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = FileLayout.fromString(simpleFileLayout, workspace)
      _ = FileLayout.fromString(bspLayout, workspace)
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("MyTests.scala")
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )
    } yield ()
  }

  def simpleFileTest(useBsp: Boolean): Future[Unit] =
    for {
      _ <- scalaCliInitialize(useBsp)(simpleFileLayout)
      _ <- server.didOpen("MyTests.scala")
      _ <- {
        if (useBsp) Future.unit
        else server.executeCommand(ServerCommands.StartScalaCliServer)
      }

      // via Scala CLI-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "(new Fo@@o).value",
        "foo.sc",
        0,
      )
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "(new Foo).va@@lue",
        "foo.sc",
        1,
      )

      // via presentation compiler, using the Scala CLI build target classpath
      _ <- assertDefinitionAtLocation(
        "utest/Tests.scala",
        "import utest.framework.{TestCallTree, Tr@@ee}",
        "utest/framework/Tree.scala",
      )

    } yield ()

  def simpleScriptTest(useBsp: Boolean): Future[Unit] =
    for {
      _ <- scalaCliInitialize(useBsp)(
        s"""/MyTests.sc
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.9"
           |//> using lib "com.lihaoyi::pprint::0.6.4"
           |
           |import foo.Foo
           |import utest._
           |
           |pprint.log(2) // top-level statement should be fine in a script
           |
           |object MyTests extends TestSuite {
           |  pprint.log(2)
           |  val tests = Tests {
           |    test("foo") {
           |      assert(2 + 2 == 4)
           |    }
           |    test("nope") {
           |      assert(2 + 2 == (new Foo).value)
           |    }
           |  }
           |}
           |
           |/foo.sc
           |class Foo {
           |  def value = 5
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("MyTests.sc")

      _ <- waitForImport(useBsp)

      // via Scala CLI-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "(new Fo@@o).value",
        "foo.sc",
        0,
      )
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "(new Foo).va@@lue",
        "foo.sc",
        1,
      )

      // via presentation compiler, using the Scala CLI build target classpath
      _ <- assertDefinitionAtLocation(
        "utest/Tests.scala",
        "import utest.framework.{TestCallTree, Tr@@ee}",
        "utest/framework/Tree.scala",
      )

    } yield ()

  test("relative-semanticdb-root") {
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""/scripts/MyTests.scala
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.9"
           |//> using lib "com.lihaoyi::pprint::0.6.4"
           |
           |import foo.Foo
           |import utest._
           |
           |object MyTests extends TestSuite {
           |  pprint.log(2)
           |  val tests = Tests {
           |    test("foo") {
           |      assert(2 + 2 == 4)
           |    }
           |    test("nope") {
           |      assert(2 + 2 == (new Foo).value)
           |    }
           |  }
           |}
           |
           |/scripts/foo.sc
           |class Foo {
           |  def value = 5
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("scripts/MyTests.scala")
      _ <- server.executeCommand(ServerCommands.StartScalaCliServer)

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "scripts/MyTests.scala",
        "(new Fo@@o).value",
        "scripts/foo.sc",
        0,
      )
    } yield ()
  }
}

object BaseScalaCliSuite {
  def scalaCliBspJsonContent(args: List[String] = Nil): String = {
    val argv = List(
      ScalaCli.javaCommand,
      "-cp",
      ScalaCli.scalaCliClassPath().mkString(File.pathSeparator),
      ScalaCli.scalaCliMainClass,
      "bsp",
      ".",
    ) ++ args
    val bsjJson = ujson.Obj(
      "name" -> "scala-cli",
      "argv" -> argv,
      "version" -> BuildInfo.scalaCliVersion,
      "bspVersion" -> "2.0.0",
      "languages" -> List("scala", "java"),
    )
    ujson.write(bsjJson)
  }

  def scalaCliIdeInputJson(args: String*): String = {
    val ideInputJson = ujson.Obj(
      "args" -> args
    )
    ujson.write(ideInputJson)
  }
}
