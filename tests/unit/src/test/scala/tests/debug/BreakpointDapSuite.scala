package tests.debug
import tests.BaseDapSuite
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.StepNavigator
import scala.meta.internal.metals.debug.Stoppage

object BreakpointDapSuite extends BaseDapSuite("debug-breakpoint") {

  // disabled, because finding enclosing class for the breakpoint line is not working
  // see [[scala.meta.internal.metals.debug.SetBreakpointsRequestHandler]]
  assertBreakpoints("preceding-class", disabled = true)(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |object Main {
                |  class Preceding
                |
                |  def main(args: Array[String]): Unit = {
                |>>  println()
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
                |  }
                |  class Succeeding
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
                |    }
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
                |    def main(args: Array[String]): Unit = {
                |      val bar = new Bar
                |      bar.call()
                |    }
                |  }
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
                |    def main(args: Array[String]): Unit = {
                |      val bar = new Bar {}
                |      bar.call()
                |    }
                |  }
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
                |    }
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
                |    }
                |}
                |""".stripMargin
  )

  // TODO: https://github.com/scalameta/metals/issues/1196
  assertBreakpoints("ambiguous", disabled = true)(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |object Main {
                |    def main(args: Array[String]): Unit = {
                |      foo.Target.call()
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

  testAsync("no-debug") {
    val workspaceLayout = DebugWorkspaceLayout(
      """|/a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |>>  println(1)
         |>>  println(2)
         |>>  println(3)
         |  }
         |}
         |""".stripMargin
    )

    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{ "a": {} }
            |
            |$workspaceLayout
            |""".stripMargin
      )
      debugger <- debugMain("a", "a.Main", Stoppage.Handler.Fail)
      _ <- debugger.initialize
      _ <- debugger.launch(debug = false)
      _ <- setBreakpoints(debugger, workspaceLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "1\n2\n3\n")
  }

  def assertBreakpoints(name: String, disabled: Boolean = false)(
      source: String
  ): Unit = {
    if (disabled) return

    testAsync(name) {
      cleanWorkspace()
      val workspaceLayout = DebugWorkspaceLayout(source)

      val layout =
        s"""|/metals.json
            |{ "a": {}, "b": {} }
            |
            |$workspaceLayout
            |""".stripMargin

      val expectedBreakpoints = workspaceLayout.files.flatMap { file =>
        file.breakpoints.map(b => Breakpoint(file.relativePath, b.startLine))
      }

      val navigator = expectedBreakpoints.foldLeft(StepNavigator(workspace)) {
        (navigator, breakpoint) =>
          navigator.at(breakpoint.relativePath, breakpoint.line + 1)(Continue)
      }

      for {
        _ <- server.initialize(layout)
        _ = assertNoDiagnostics()
        debugger <- debugMain("a", "a.Main", navigator)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, workspaceLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield ()
    }
  }

  private final case class Breakpoint(relativePath: String, line: Int)
}
