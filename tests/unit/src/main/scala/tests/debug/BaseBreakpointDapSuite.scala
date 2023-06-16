package tests.debug

import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.LibraryBreakpoints
import scala.meta.internal.metals.debug.Stoppage

import tests.BaseDapSuite
import tests.BuildServerInitializer
import tests.BuildToolLayout

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
abstract class BaseBreakpointDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout,
) extends BaseDapSuite(suiteName, initializer, buildToolLayout) {

  assertBreakpoints("preceding-class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  class Preceding
                |
                |  def main(args: Array[String]): Unit = {
                |>>  println()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("succeeding-class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  // this line must remain empty
                |  def main(args: Array[String]): Unit = {
                |>>  println()
                |    System.exit(0)
                |  }
                |  class Succeeding
                |}
                |""".stripMargin
  )

  assertBreakpoints("inner-class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    class Inside {
                |       def go = {
                |>>       println()
                |       }
                |    }
                |    (new Inside).go
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("object")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Bar {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("object-apply")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Bar {
                |  def apply(): Boolean = {
                |>>  true
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Bar()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("object-unapply")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Bar {
                |  def unapply(any: Any) :Boolean = {
                |>>  true
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    this match {
                |      case Bar() =>
                |      case _ =>
                |    }
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("trait")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |trait Bar {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Bar {}
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |class Bar {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Bar
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("anonymous")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |class Foo
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Foo {
                |      def call() = {
                |>>      println()
                |      }
                |    }
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("case-class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |case class Bar() {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Bar
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("case-class-unapply")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |case class Bar() {
                |  def unapply(arg: Any): Option[Int] = {
                |>>    Some(1)
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val bar = Bar()
                |    this match {
                |      case bar(1) => println()
                |      case _ =>
                |    }
                |    System.exit(0)
                |  }
                |}

                |""".stripMargin
  )

  assertBreakpoints("companion")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |case class Bar()
                |
                |object Bar {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("companion-apply")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |case class Bar()
                |
                |object Bar {
                |  def apply() = {
                |>>  println()
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Bar()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("for-comprehension")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    for {
                |>>    x <- List()
                |    } println(x)
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("for-each-comprehension")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    for {
                |    x <- List(1)
                |  } {
                |>>    println(x)
                |    }
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("for-yield")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    for {
                |    x <- List(1)
                |    } yield {
                |>>    println(x)
                |    }
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("nested object")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |trait Foo {
                |  object Bar {
                |    def call() = {
                |>>    println()
                |    }
                |  }
                |}
                |
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val foo = new Foo {}
                |    foo.Bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("nested class")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |trait Foo {
                |  class Bar {
                |    def call() = {
                |>>    println()
                |    }
                |  }
                |}
                |
                |object Main extends Foo {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Bar
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("nested trait")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |trait Foo {
                |  trait Bar {
                |    def call() = {
                |>>    println()
                |    }
                |  }
                |}
                |
                |object Main extends Foo {
                |  def main(args: Array[String]): Unit = {
                |    val bar = new Bar {}
                |    bar.call()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("package-object")(
    source = """|/a/src/main/scala/a/package.scala
                |package object a {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |    def main(args: Array[String]): Unit = {
                |      call()
                |      System.exit(0)
                |    }
                |}
                |""".stripMargin
  )

  assertBreakpoints("lambda")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    List(1).foreach{ e =>
                |>>    println(e)
                |    }
                |    System.exit(0);
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-enum")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Foo.A.call()
                |    System.exit(0)
                |  }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |enum Foo {
                |  A;
                |
                |  void call() {
                |>>  System.out.println();
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("multi-files")(
    source = """|/a/src/main/scala/a/Foo.scala
                |package a
                |
                |trait Foo {
                |  object Bar {
                |    def call() = {
                |>>    println()
                |    }
                |  }
                |}
                |
                |/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val foo = new Foo {}
                |    foo.Bar.call()
                |>>  System.exit(0)
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-static-method")(
    source = """|/a/src/main/scala/a/Main.java
                |package a;
                |
                |public class Main {
                |    public static void main(String[] args) {
                |        Foo.call();
                |        System.exit(0);
                |    }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |class Foo {
                |  static void call(){
                |>>  System.out.println();
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-lambda")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Foo.call()
                |    System.exit(0)
                |  }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |import java.util.stream.Stream;
                |
                |class Foo {
                |  static void call(){
                |    Stream.of(1).forEach(e ->
                |>>    System.out.println(e)
                |    );
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-nested")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Foo.call()
                |    System.exit(0)
                |  }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |class Foo {
                |  class Bar {
                |    void call(){
                |>>    System.out.println();
                |    }
                |  }
                |
                |  static void call() {
                |    Foo foo = new Foo();
                |    Bar bar = foo.new Bar();
                |    bar.call();
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-preceeding")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val foo = new Foo()
                |    foo.call()
                |    System.exit(0)
                |  }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |class Foo {
                |  class Bar {}
                |
                |  void call() {
                |>>  System.out.println();
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("java-anonymous")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    Foo.call()
                |    System.exit(0)
                |  }
                |}
                |
                |/a/src/main/java/a/Foo.java
                |package a;
                |
                |class Bar {
                |    void call() {};
                |}
                |
                |class Foo {
                |  static void call() {
                |    Bar bar = new Bar() {
                |      @Override
                |      void call() {
                |>>      System.out.println();
                |      }
                |    };
                |    bar.call();
                |  }
                |}
                |""".stripMargin
  )

  assertBreakpoints("not-matching-filename")(
    source = """|/a/src/main/scala/a/not-matching.scala
                |package a
                |
                |object Foo {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |    def main(args: Array[String]): Unit = {
                |      a.Foo.call()
                |      System.exit(0)
                |    }
                |}
                |""".stripMargin
  )

  assertBreakpoints("not-matching-package")(
    source = """|/a/src/main/scala/Foo.scala
                |package not.matching
                |
                |object Foo {
                |  def call() = {
                |>>  println()
                |  }
                |}
                |
                |/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |    def main(args: Array[String]): Unit = {
                |      not.matching.Foo.call()
                |      System.exit(0)
                |    }
                |}
                |""".stripMargin
  )

  assertBreakpoints("clashing-symbols")(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |    def main(args: Array[String]): Unit = {
                |      foo.Target.call()
                |      System.exit(0)
                |    }
                |}
                |
                |/a/src/main/scala/Target.scala
                |package foo
                |
                |object Target {
                |  def call() = {
                |>>  println("Correct Target")
                |  }
                |}
                |
                |/b/src/main/scala/Target.scala
                |package foo
                |
                |object Target {
                |  def call() = {
                |    println("Incorrect Target")
                |  }
                |}
                |""".stripMargin
  )

  test("remove-breakpoints") {
    val debugLayout = DebugWorkspaceLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |>>  println(2)
         |>>  println(3)
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin,
      workspace,
    )
    val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)

    for {
      _ <- initialize(workspaceLayout)
      debugger <- debugMain("a", "a.Main", Stoppage.Handler.Fail)
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- removeBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "1\n2\n3\n")
  }

  test("library-breakpoints", withoutVirtualDocs = true) {
    def debugLayout = new DebugWorkspaceLayout(
      List(
        LibraryBreakpoints(
          server
            .toPathFromSymbol("scala.Predef", "scala/Predef.scala"),
          List(404),
        )
      )
    )

    val workspaceLayout = buildToolLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |    println(0)
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin,
      scalaVersion,
    )

    for {
      _ <- initialize(workspaceLayout)
      debugger <- debugMain("a", "a.Main", Stoppage.Handler.Fail)
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "0\n")
  }

  test("remove-some-breakpoints") {
    val firstBreakpoints = DebugWorkspaceLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |>>  println(2)
         |>>  println(3)
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin,
      workspace,
    )

    val modifiedBreakpoints = DebugWorkspaceLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |>>  println(2)
         |    println(3)
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin,
      workspace,
    )
    val workspaceLayout =
      buildToolLayout(firstBreakpoints.toString, scalaVersion)

    val navigator = navigateExpectedBreakpoints(modifiedBreakpoints)
    for {
      _ <- initialize(workspaceLayout)
      debugger <- debugMain("a", "a.Main", navigator)
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, firstBreakpoints)
      _ <- setBreakpoints(debugger, modifiedBreakpoints)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "1\n2\n3\n")
  }

  test("no-debug") {
    val debugLayout = DebugWorkspaceLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |>>  println(2)
         |>>  println(3)
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin,
      workspace,
    )
    val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)

    for {
      _ <- initialize(workspaceLayout)
      debugger <- debugMain("a", "a.Main", Stoppage.Handler.Fail)
      _ <- debugger.initialize
      _ <- debugger.launch(debug = false)
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "1\n2\n3\n")
  }
}
