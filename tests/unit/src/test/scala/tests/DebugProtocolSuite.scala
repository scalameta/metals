package tests

import java.util.Collections.emptyList
import java.util.Collections.singletonList
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.debug.WorkspaceErrorsException
import scala.meta.internal.metals.Messages

class DebugProtocolSuite extends BaseLspSuite("debug-protocol") {

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
           |    print("Foo")
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
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Foo")
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
           |    print(args(0))
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
      _ <- server.didSave("a/src/main/scala/a/Main.scala") { _ =>
        """|package c
           |object Other {
           |  val a : Int = ""
           |}}""".stripMargin
      }
      result <- server
        .startDebuggingUnresolved(
          new DebugUnresolvedMainClassParams(
            "a.Main",
            "a",
            singletonList("Foo")
          ).toJson
        )
        .recover {
          case WorkspaceErrorsException =>
            server.statusBarHistory
        }
    } yield assertContains(
      result.toString(),
      Messages.DebugErrorsPresent.text
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
      result <- server
        .startDebuggingUnresolved(
          new DebugUnresolvedTestClassParams(
            "a.Foo"
          ).toJson
        )
        .recover {
          case WorkspaceErrorsException =>
            server.statusBarHistory
        }
    } yield assertContains(
      result.toString(),
      Messages.DebugErrorsPresent.text
    )
  }
}
