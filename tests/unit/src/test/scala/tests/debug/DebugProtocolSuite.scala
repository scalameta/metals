package tests.debug

import java.util.Collections.emptyList
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseLspSuite

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
}
