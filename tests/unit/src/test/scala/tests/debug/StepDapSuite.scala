package tests.debug

import tests.BaseDapSuite
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugFileLayout
import scala.meta.internal.metals.debug.StepNavigator

object StepDapSuite extends BaseDapSuite("debug-step") {
  assertSteps("step-out")(
    sources = List(
      """|a/src/main/scala/Main.scala
         |package a
         |
         |object Main {
         |  def foo() = {
         |>>  println()
         |  }
         |
         |  def main(args: Array[String]): Unit = {
         |    foo()
         |  }
         |}
         |""".stripMargin
    ),
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepOut)
        .at("a/src/main/scala/Main.scala", line = 9)(Continue)
  )

  assertSteps("step-over")(
    sources = List(
      """|a/src/main/scala/Main.scala
         |package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |    println(2)
         |  }
         |}
         |""".stripMargin
    ),
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepOver)
        .at("a/src/main/scala/Main.scala", line = 6)(Continue)
  )

  assertSteps("step-into-java")(
    sources = List(
      """|a/src/main/scala/a/ScalaMain.scala
         |package a
         |
         |object ScalaMain {
         |  def main(args: Array[String]): Unit = {
         |>>  JavaClass.foo(7)
         |  }
         |}""".stripMargin,
      """|a/src/main/java/a/JavaClass.java
         |package a;
         |
         |class JavaClass {
         |  static void foo(int i){
         |    System.out.println(i);
         |  }
         |}""".stripMargin
    ),
    main = "a.ScalaMain",
    instrument = steps =>
      steps
        .at("a/src/main/scala/a/ScalaMain.scala", line = 5)(StepIn)
        .at("a/src/main/java/a/JavaClass.java", line = 5)(StepOut)
        .at("a/src/main/scala/a/ScalaMain.scala", line = 5)(Continue)
  )

  assertSteps("step-into-scala-lib")(
    sources = List(
      """|a/src/main/scala/Main.scala
         |package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println("foo")
         |  }
         |}
         |""".stripMargin
    ),
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepIn)
        .at(".metals/readonly/scala/Predef.scala", line = 397)(Continue)
  )

  assertSteps("step-into-java-lib")(
    sources = List(
      """|a/src/main/scala/Main.scala
         |package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  System.out.println("foo")
         |  }
         |}
         |""".stripMargin
    ),
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepIn)
        .at(".metals/readonly/java/io/PrintStream.java", line = 805)(Continue)
  )

  assertSteps("stops-on-different-class-in-same-file")(
    sources = List(
      """|a/src/main/scala/a/Main.scala
         |package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |    val foo = new Foo
         |>>  foo.call()
         |  }
         |}
         |
         |class Foo {
         |  def call() = {
         |>>  println("foo")
         |  }
         |}
         |""".stripMargin
    ),
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/a/Main.scala", line = 6)(Continue)
        .at("a/src/main/scala/a/Main.scala", line = 12)(Continue)
  )

  def assertSteps(name: String)(
      sources: List[String],
      main: String,
      instrument: StepNavigator => StepNavigator
  ): Unit = {
    testAsync(name) {
      cleanWorkspace()
      val fileLayouts = sources.map(DebugFileLayout.apply)

      val layout =
        s"""|/metals.json
            |{ "a": {} }
            |
            |${fileLayouts.map(_.layout).mkString("\n")}
            |""".stripMargin

      val navigator = instrument(StepNavigator(workspace))

      for {
        _ <- server.initialize(layout)
        debugger <- debugMain("a", main, navigator)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, fileLayouts)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield ()
    }
  }
}
