package tests.scalacli

import scala.concurrent.Future

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import tests.FileLayout

class ScalaCliSuite extends BaseScalaCliSuite(V.scala3) {

  private def simpleFileTest(useBsp: Boolean): Future[Unit] =
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

  private def simpleScriptTest(useBsp: Boolean): Future[Unit] =
    for {
      _ <- scalaCliInitialize(useBsp)(
        s"""/MyTests.sc
           |#!/usr/bin/env -S scala-cli shebang --java-opt -Xms256m --java-opt -XX:MaxRAMPercentage=80 
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.10"
           |//> using lib "com.lihaoyi::pprint::0.6.6"
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
      // make sure we don't get errors connected to shebang
      parserDiagnostics = client.diagnostics
        .get(workspace.resolve("MyTests.sc"))
        .toList
        .flatten
        .filter(_.getSource() == "scalameta")
      _ = assert(
        parserDiagnostics.isEmpty,
        s"Expected no scalameta errors, got: $parserDiagnostics",
      )

    } yield ()

  private val simpleFileLayout =
    s"""|/MyTests.scala
        |//> using scala "$scalaVersion"
        |//> using lib "com.lihaoyi::utest::0.7.10"
        |//> using lib "com.lihaoyi::pprint::0.6.6"
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

  test(s"simple-file-bsp") {
    simpleFileTest(useBsp = true)
  }

  test(s"simple-file-manual") {
    simpleFileTest(useBsp = false)
  }

  test(s"simple-script-bsp") {
    simpleScriptTest(useBsp = true)
  }

  test(s"simple-script-manual") {
    simpleScriptTest(useBsp = false)
  }

  test("connecting-scalacli") {
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

  test("relative-semanticdb-root") {
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""/scripts/MyTests.scala
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.10"
           |//> using lib "com.lihaoyi::pprint::0.6.6"
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
