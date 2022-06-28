package tests

import java.io.File

import scala.concurrent.Future

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.scalacli.ScalaCli

import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageActionItem

abstract class BaseScalaCliSuite(scalaVersion: String)
    extends BaseLspSuite(s"scala-cli-$scalaVersion")
    with ScriptsAssertions {

  override def munitIgnore: Boolean =
    !isValidScalaVersionForEnv(scalaVersion)

  override def newServer(workspaceName: String): Unit = {
    super.newServer(workspaceName)
    val previousShowMessageRequestHandler =
      server.client.showMessageRequestHandler
    server.client.showMessageRequestHandler = { params =>
      if (params == Messages.ImportAmmoniteScript.params())
        Some(new MessageActionItem(Messages.ImportAmmoniteScript.dismiss))
      else
        previousShowMessageRequestHandler(params)
    }
  }

  private def escape(s: String): String =
    s.replace("\\", "\\\\")
  private def bspLayout =
    s"""/.bsp/scala-cli.json
       |{
       |  "name": "scala-cli",
       |  "argv": [
       |    "${escape(ScalaCli.javaCommand)}",
       |    "-cp",
       |    "${escape(ScalaCli.scalaCliClassPath().mkString(File.pathSeparator))}",
       |    "${ScalaCli.scalaCliMainClass}",
       |    "bsp",
       |    "."
       |  ],
       |  "version": "${BuildInfo.scalaCliVersion}",
       |  "bspVersion": "2.0.0",
       |  "languages": [
       |    "scala",
       |    "java"
       |  ]
       |}
       |
       |/.scala-build/ide-inputs.json
       |{
       |  "args": [
       |    "."
       |  ]
       |}
       |
       |""".stripMargin

  private def scalaCliInitialize(layout: String): Future[InitializeResult] =
    initialize(bspLayout + layout)

  test("simple file") {
    simpleFileTest()
  }
  test("simple script") {
    simpleScriptTest()
  }

  def simpleFileTest(): Future[Unit] =
    for {
      _ <- scalaCliInitialize(
        s"""/MyTests.scala
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
      )
      _ <- server.didOpen("MyTests.scala")

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

  def simpleScriptTest(): Future[Unit] =
    for {
      _ <- scalaCliInitialize(
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

}
