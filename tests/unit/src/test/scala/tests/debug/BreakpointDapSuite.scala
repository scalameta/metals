package tests.debug
import tests.BaseDapSuite
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugFileLayout
import scala.meta.internal.metals.debug.StepNavigator

object BreakpointDapSuite extends BaseDapSuite("debug-breakpoint") {

  // disabled, because finding enclosing class for the breakpoint line is not working
  // see [[scala.meta.internal.metals.debug.SetBreakpointsRequestHandler]]
  assertFileBreakpoints("preceding-class", disabled = true)(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("succeeding-class")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("object")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("object-apply")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("object-unapply")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("trait")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("class")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("case-class")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("case-class-unapply")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("companion")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("companion-apply")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("for-comprehension")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("for-each-comprehension")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("for-yield")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("nested object")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("nested class")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertFileBreakpoints("nested trait")(
    source = """|a/src/main/scala/a/Main.scala
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

  assertProjectBreakpoints("package-object")(
    sources = List(
      """|a/src/main/scala/a/package.scala
         |package object a {
         |  def call() = {
         |>>  println()
         |  }
         |}
         |""".stripMargin,
      """|a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |    def main(args: Array[String]): Unit = {
         |      call()
         |    }
         |}
         |""".stripMargin
    )
  )

  assertProjectBreakpoints("not-matching-filename")(
    sources = List(
      """|a/src/main/scala/a/not-matching.scala
         |package a
         |
         |object Foo {
         |  def call() = {
         |>>  println()
         |  }
         |}
         |""".stripMargin,
      """|a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |    def main(args: Array[String]): Unit = {
         |      a.Foo.call()
         |    }
         |}
         |""".stripMargin
    )
  )

  assertProjectBreakpoints("not-matching-package")(
    sources = List(
      """|a/src/main/scala/Foo.scala
         |package not.matching
         |
         |object Foo {
         |  def call() = {
         |>>  println()
         |  }
         |}
         |""".stripMargin,
      """|a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |    def main(args: Array[String]): Unit = {
         |      not.matching.Foo.call()
         |    }
         |}
         |""".stripMargin
    )
  )

  // TODO OnDemandSymbolIndex doesn't distinguish between build targets
  assertProjectBreakpoints("ambiguous", disabled = true)(
    sources = List(
      """|a/src/main/scala/Target.scala
         |package foo
         |
         |object Target {
         |  def call() = {
         |>>  println("Correct Target")
         |  }
         |}
         |""".stripMargin,
      """|b/src/main/scala/Target.scala
         |package foo
         |
         |object Target {
         |  def call() = {
         |    println("Incorrect Target")
         |  }
         |}
         |""".stripMargin,
      """|a/src/main/scala/a/Main.scala
         |package a
         |object Main {
         |    def main(args: Array[String]): Unit = {
         |      foo.Target.call()
         |    }
         |}
         |""".stripMargin
    )
  )

  def assertFileBreakpoints(name: String, disabled: Boolean = false)(
      source: String
  ): Unit = {
    assertProjectBreakpoints(name, disabled)(List(source))
  }

  def assertProjectBreakpoints(name: String, disabled: Boolean = false)(
      sources: List[String]
  ): Unit = {
    if (disabled) return

    testAsync(name) {
      cleanWorkspace()
      val fileLayouts = sources.map(DebugFileLayout.apply)

      val layout =
        s"""|/metals.json
            |{ "a": {}, "b": {} }
            |
            |${fileLayouts.map(_.layout).mkString("\n")}
            |""".stripMargin

      val expectedBreakpoints = fileLayouts.flatMap { file =>
        // lines from FileLayout start at 0
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
        _ <- setBreakpoints(debugger, fileLayouts)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield ()
    }
  }

  private final case class Breakpoint(relativePath: String, line: Int)
}
