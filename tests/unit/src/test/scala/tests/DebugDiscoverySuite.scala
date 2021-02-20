package tests

import scala.meta.internal.metals.DebugFullyUnresolvedParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.debug.BuildTargetContainsNoMainException
import scala.meta.internal.metals.debug.WorkspaceErrorsException
import scala.meta.internal.metals.debug.NoTestsFoundException

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugDiscoverySuite extends BaseDapSuite("debug-discovery") {

  test("run") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugFullyUnresolvedParams(
          s"file://${workspace}/a/src/main/scala/a/Main.scala",
          "run"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "oranges are nice")
  }

  test("run-multiple") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main extends App {
           |    print("what about grapes?")
           |    System.exit(0)
           |}
           |
           |object Other extends App {
           |    print("o, and pears!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      // TestingClient handles the choice here to pick a.Main
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugFullyUnresolvedParams(
          s"file://${workspace}/a/src/main/scala/a/Main.scala",
          "run"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "what about grapes?")
  }

  test("no-main") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff"
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      result <- server
        .startDebuggingUnresolved(
          new DebugFullyUnresolvedParams(
            s"file://${workspace}/a/src/main/scala/a/Main.scala",
            "run"
          ).toJson
        )
        .recover { case e: BuildTargetContainsNoMainException =>
          e
        }
    } yield assertNoDiff(
      result.toString,
      BuildTargetContainsNoMainException("a").toString()
    )
  }

  test("workspace-error") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff... but with no ending!
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      result <- server
        .startDebuggingUnresolved(
          new DebugFullyUnresolvedParams(
            s"file://${workspace}/a/src/main/scala/a/Main.scala",
            "run"
          ).toJson
        )
        .recover { case WorkspaceErrorsException =>
          WorkspaceErrorsException
        }
    } yield assertNoDiff(
      result.toString,
      WorkspaceErrorsException.toString()
    )
  }

  test("testFile") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.0.5"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugFullyUnresolvedParams(
          s"file://${workspace}/a/src/main/scala/a/Foo.scala",
          "testFile"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("testTarget") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.0.5"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {}
           |}
           |/a/src/main/scala/a/Bar.scala
           |package a
           |class Bar extends org.scalatest.FunSuite {
           |  test("bart") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Bar.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugFullyUnresolvedParams(
          s"file://${workspace}/a/src/main/scala/a/Bar.scala",
          "testTarget"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("no-tests") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/NotATest.scala
           |package a
           |class NotATest {
           |    print("I'm not a test!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/NotATest.scala")
      result <- server
        .startDebuggingUnresolved(
          new DebugFullyUnresolvedParams(
            s"file://${workspace}/a/src/main/scala/a/NotATest.scala",
            "testTarget"
          ).toJson
        )
        .recover { case e: NoTestsFoundException => e }
    } yield assertNoDiff(
      result.toString(),
      NoTestsFoundException("build target", "a").toString()
    )
  }
}
