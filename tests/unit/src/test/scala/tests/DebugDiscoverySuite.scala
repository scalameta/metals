package tests

import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.debug.BuildTargetContainsNoMainException
import scala.meta.internal.metals.debug.DotEnvFileParser.InvalidEnvFileException
import scala.meta.internal.metals.debug.NoTestsFoundException
import scala.meta.internal.metals.debug.SemanticDbNotFoundException
import scala.meta.internal.metals.debug.WorkspaceErrorsException
import scala.meta.io.AbsolutePath

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugDiscoverySuite extends BaseDapSuite("debug-discovery") {
  private val mainPath = "a/src/main/scala/a/Main.scala"
  private val fooPath = "a/src/main/scala/a/Foo.scala"
  private val barPath = "a/src/main/scala/a/Bar.scala"

  test("run") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
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
           |/${mainPath}
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
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
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
           |/${mainPath}
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff"
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(mainPath).toURI.toString,
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
           |/${mainPath}
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff... but with no ending!
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(mainPath).toURI.toString,
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

  test("invalid-env") {
    val fakePath = workspace + "fake-path"

    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Stuff extends App {
           |  val veryHardCode = "wowza"
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.didSave(mainPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            path = server.toPath(mainPath).toURI.toString,
            runType = "run",
            envFile = fakePath
          ).toJson
        )
        .recover { case e: InvalidEnvFileException => e }
    } yield assertNoDiff(
      result.toString,
      InvalidEnvFileException(AbsolutePath(fakePath)).toString()
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
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
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
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {}
           |}
           |/${barPath}
           |package a
           |class Bar extends org.scalatest.FunSuite {
           |  test("bart") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(barPath)
      _ <- server.didSave(barPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(barPath).toURI.toString,
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
    val notATestPath = "a/src/main/scala/a/NotATest.scala"
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${notATestPath}
           |package a
           |class NotATest {
           |    print("I'm not a test!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(notATestPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(notATestPath).toURI.toString,
            "testTarget"
          ).toJson
        )
        .recover { case e: NoTestsFoundException => e }
    } yield assertNoDiff(
      result.toString(),
      NoTestsFoundException("build target", "a").toString()
    )
  }

  test("no-semanticdb") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.0.5"]
           |  }
           |}
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      _ = cleanCompileCache("a")
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(fooPath).toURI.toString,
            "testFile"
          ).toJson
        )
        .recover { case SemanticDbNotFoundException =>
          SemanticDbNotFoundException
        }
    } yield assertNoDiff(
      result.toString,
      SemanticDbNotFoundException.toString()
    )
  }
}
