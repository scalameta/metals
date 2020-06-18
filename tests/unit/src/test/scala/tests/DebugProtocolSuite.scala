package tests

import java.util.Collections.emptyList
import java.util.Collections.singletonList

import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.WorkspaceErrorsException

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugProtocolSuite extends BaseDapSuite("debug-protocol") {

  test("start") {
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
           |    val foo = sys.props.getOrElse("property", "")
           |    val bar = args(0)
           |    print(foo + bar)
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass(
          "a.Main",
          List("Bar").asJava,
          List("-Dproperty=Foo").asJava
        )
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBar")
  }

  test("broken-workspace") {

    def startDebugging() =
      server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", Nil.asJava, Nil.asJava)
      )
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
           |    println("Hello world")
           |    System.exit(0)
           |  }
           |
           |""".stripMargin
      )
      failed = startDebugging()
      debugger <- failed.recoverWith {
        case e: ResponseErrorException =>
          server
            .didSave("a/src/main/scala/a/Main.scala") { text => text + "}" }
            .flatMap(_ => startDebugging())
      }
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Hello world")
  }

  test("disconnect") {
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
           |    synchronized(wait())
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", emptyList(), emptyList())
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.disconnect
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "")
  }

  test("restart") {
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
           |    println("Foo")
           |    synchronized(wait())
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", emptyList(), emptyList())
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.awaitOutput("Foo\n")

      _ <- server.didSave("a/src/main/scala/a/Main.scala")(
        _.replaceAll("Foo", "Bar")
      )
      _ <- debugger.restart

      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.awaitOutput("Bar\n")
      _ <- debugger.disconnect
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Bar\n")
  }

  test("run-unresolved-params") {
    cleanCompileCache("a")
    cleanWorkspace()
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
           |    print(args(0))
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedMainClassParams(
          "a.Main",
          "a",
          singletonList("Foo")
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Foo")
  }

  test("run-unrelated-error") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {},
           |  "c": {"dependsOn": ["a"]}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print(args(0))
           |    System.exit(0)
           |  }
           |}
           |/c/src/main/scala/c/Other.scala
           |package c
           |object Other {
           |  val a : Int = ""
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("c/src/main/scala/c/Other.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedMainClassParams(
          "a.Main",
          "a",
          singletonList("Foo")
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Foo")
  }

  test("abort-run-broken-workspace") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {"dependsOn": ["c"]},
           |  "c": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    println(c.Other.a)
           |    System.exit(0)
           |  }
           |}
           |/c/src/main/scala/c/Other.scala
           |package c
           |object Other {
           |  val a : Int = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didSave("c/src/main/scala/c/Other.scala") { _ =>
        """|package c
           |object Other {
           |  val a : Int = ""
           |}""".stripMargin
      }
      result <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedMainClassParams(
              "a.Main",
              "a",
              singletonList("Foo")
            ).toJson
          )
          .recover {
            case WorkspaceErrorsException =>
              WorkspaceErrorsException
          }
    } yield assertDiffEqual(
      result.toString(),
      WorkspaceErrorsException.toString()
    )
  }

  test("test-unresolved-params") {
    cleanCompileCache("a")
    cleanWorkspace()
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
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams(
          "a.Foo"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("abort-test-broken-workspace") {
    cleanCompileCache("a")
    cleanWorkspace()
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
      _ <- server.didSave("a/src/main/scala/a/Foo.scala") { _ =>
        """|package a
           |class Foo extends org.scalatest.FunSuite {
           |  test("foo") {
           |    val a : Int = ""
           |  }
           |}""".stripMargin
      }
      result <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedTestClassParams(
              "a.Foo"
            ).toJson
          )
          .recover {
            case WorkspaceErrorsException =>
              WorkspaceErrorsException
          }
    } yield assertContains(
      result.toString(),
      WorkspaceErrorsException.toString()
    )
  }
}
