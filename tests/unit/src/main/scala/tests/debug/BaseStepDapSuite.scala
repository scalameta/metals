package tests.debug

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.StepNavigator

import munit.Location
import munit.TestOptions
import tests.BaseDapSuite
import tests.BuildServerInitializer
import tests.BuildToolLayout
import tests.TestingServer

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
abstract class BaseStepDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout,
) extends BaseDapSuite(suiteName, initializer, buildToolLayout) {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  assertSteps("step-out")(
    sources = """|/a/src/main/scala/Main.scala
                 |package a
                 |
                 |object Main {
                 |  def foo() = {
                 |>>  println()
                 |  }
                 |
                 |  def main(args: Array[String]): Unit = {
                 |    foo()
                 |    System.exit(0)
                 |  }
                 |}
                 |""".stripMargin,
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepOut)
        .at("a/src/main/scala/Main.scala", line = 10)(Continue),
  )

  assertSteps("step-over")(
    sources = """|/a/src/main/scala/Main.scala
                 |package a
                 |
                 |object Main {
                 |  def main(args: Array[String]): Unit = {
                 |>>  println(1)
                 |    println(2)
                 |    System.exit(0)
                 |  }
                 |}
                 |""".stripMargin,
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/Main.scala", line = 5)(StepOver)
        .at("a/src/main/scala/Main.scala", line = 6)(Continue),
  )

  assertSteps("step-into-java")(
    sources = """|/a/src/main/scala/a/ScalaMain.scala
                 |package a
                 |
                 |object ScalaMain {
                 |  def main(args: Array[String]): Unit = {
                 |>>  JavaClass.foo(7)
                 |    System.exit(0)
                 |  }
                 |}
                 |
                 |/a/src/main/java/a/JavaClass.java
                 |package a;
                 |
                 |class JavaClass {
                 |  static void foo(int i){
                 |    System.out.println(i);
                 |  }
                 |}""".stripMargin,
    main = "a.ScalaMain",
    instrument = steps =>
      steps
        .at("a/src/main/scala/a/ScalaMain.scala", line = 5)(StepIn)
        .at("a/src/main/java/a/JavaClass.java", line = 5)(StepOut)
        .at("a/src/main/scala/a/ScalaMain.scala", line = 6)(Continue),
    focusFile = "a/src/main/scala/a/ScalaMain.scala",
  )

  if (suiteName != "mill-debug-step") { // TODO: delete condition after https://github.com/com-lihaoyi/mill/issues/3148
    assertSteps("step-into-scala-lib", withoutVirtualDocs = true)(
      sources = """|/a/src/main/scala/Main.scala
                   |package a
                   |
                   |object Main {
                   |  def main(args: Array[String]): Unit = {
                   |>>  println("foo")
                   |    System.exit(0)
                   |  }
                   |}
                   |""".stripMargin,
      main = "a.Main",
      instrument = steps => {
        steps
          .at("a/src/main/scala/Main.scala", line = 5)(StepIn)
          .atDependency(
            server.toPathFromSymbol("scala.Predef", "scala/Predef.scala"),
            line = if (scalaVersion.startsWith("2.13")) 427 else 405,
          )(Continue)
      },
    )

    assertSteps("step-into-java-lib", withoutVirtualDocs = true)(
      sources = """|/a/src/main/scala/Main.scala
                   |package a
                   |
                   |object Main {
                   |  def main(args: Array[String]): Unit = {
                   |>>  System.out.println("foo")
                   |    System.exit(0)
                   |  }
                   |}
                   |""".stripMargin,
      main = "a.Main",
      instrument = steps => {
        val (javaLibFile, javaLibLine) =
          if (isJava24) ("java.base/java/io/PrintStream.java", 1003)
          else if (isJava21) ("java.base/java/io/PrintStream.java", 1167)
          else if (isJava17) ("java.base/java/io/PrintStream.java", 1027)
          else ("java.base/java/io/PrintStream.java", 881)
        steps
          .at("a/src/main/scala/Main.scala", line = 5)(StepIn)
          .atDependency(
            server.toPathFromSymbol("java.io.PrintStream", javaLibFile),
            javaLibLine,
          )(Continue)
      },
    )
  }

  assertSteps("stops-on-different-class-in-same-file")(
    sources = """|/a/src/main/scala/a/Main.scala
                 |package a
                 |
                 |object Main {
                 |  def main(args: Array[String]): Unit = {
                 |    val foo = new Foo
                 |>>  foo.call()
                 |    System.exit(0)
                 |  }
                 |}
                 |
                 |class Foo {
                 |  def call() = {
                 |>>  println("foo")
                 |  }
                 |}
                 |""".stripMargin,
    main = "a.Main",
    instrument = steps =>
      steps
        .at("a/src/main/scala/a/Main.scala", line = 6)(Continue)
        .at("a/src/main/scala/a/Main.scala", line = 13)(Continue),
    focusFile = "a/src/main/scala/a/Main.scala",
  )

  def assertSteps(name: TestOptions, withoutVirtualDocs: Boolean = false)(
      sources: String,
      main: String,
      instrument: StepNavigator => StepNavigator,
      focusFile: String = "a/src/main/scala/Main.scala",
  )(implicit loc: Location): Unit = {
    test(name, withoutVirtualDocs) {
      cleanWorkspace()
      val debugLayout = DebugWorkspaceLayout(sources, workspace)
      val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)

      for {
        _ <- initialize(workspaceLayout)
        _ <- server.server.indexingPromise.future
        _ <- server.didFocus(focusFile)
        navigator = instrument(StepNavigator(workspace))
        debugger <- debugMain("a", main, navigator)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, debugLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield ()
    }
  }

}
