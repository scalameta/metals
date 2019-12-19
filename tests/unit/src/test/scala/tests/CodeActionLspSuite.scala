package tests

import scala.meta.internal.metals.QuickFix.ImportMissingSymbol
import scala.meta.internal.metals.Refactoring.UseNamedArguments
import scala.meta.internal.metals.MetalsEnrichments._

object CodeActionLspSuite extends BaseLspSuite("codeAction") {

  check(
    "auto-import",
    """|package a
       |
       |object A {
       |  val f = Fut@@ure.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.label("Future", "scala.concurrent")}
        |${ImportMissingSymbol.label("Future", "java.util.concurrent")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-basic",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-cursor-in-function-name",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomethi@@ng(123, "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-different-class",
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  val doer = new Doer(true)
       |  val x = doer.doSomething(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  val doer = new Doer(true)
       |  val x = doer.doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-scala-Predef",
    """|package a
       |
       |object A {
       |  requi@@re(1 == 1)
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  require(requirement = 1 == 1)
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-scala-stdlib",
    """|package a
       |
       |import scala.io.Source
       |
       |object A {
       |  val source = Source.fromString(@@"hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |import scala.io.Source
       |
       |object A {
       |  val source = Source.fromString(s = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-partially-named",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-nested",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def addOne(n: Int): Int = n + 1
       |  val x = doSomething(addOn@@e(123), "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def addOne(n: Int): Int = n + 1
       |  val x = doSomething(addOne(n = 123), "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-chained",
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  def createDoer(z: Boolean): Doer = new Doer(z)
       |  val x = createDoer(true).doSomething(@@123, "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  def createDoer(z: Boolean): Doer = new Doer(z)
       |  val x = createDoer(true).doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "use-named-arguments-overload",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def doSomething(a: Int, b: String, c: Boolean): Boolean = true
       |  val x = doSomething(@@123, "hello", false)
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def doSomething(a: Int, b: String, c: Boolean): Boolean = true
       |  val x = doSomething(a = 123, b = "hello", c = false)
       |}
       |""".stripMargin
  )

  // TODO constructors and apply methods do not work
  //check(
  //"use-named-arguments-constructor",
  //"""|package a
  //|
  //|class Thing(foo: Int, bar: String)
  //|
  //|object A {
  //|  val x = new Thing(123,@@ "hello")
  //|}
  //|""".stripMargin,
  //UseNamedArguments.title,
  //"""|package a
  //|
  //|class Thing(foo: Int, bar: String)
  //|
  //|object A {
  //|  val x = new Thing(foo = 123, bar = "hello")
  //|}
  //|""".stripMargin
  //)

  //check(
  //"use-named-arguments-case-class-apply",
  //"""|package a
  //|
  //|case class Thing(foo: Int, bar: String)
  //|
  //|object A {
  //|  val x = Thing(123,@@ "hello")
  //|}
  //|""".stripMargin,
  //UseNamedArguments.title,
  //"""|package a
  //|
  //|case class Thing(foo: Int, bar: String)
  //|
  //|object A {
  //|  val x = Thing(foo = 123, bar = "hello")
  //|}
  //|""".stripMargin
  //)

  def check(
      name: String,
      input: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0
  ): Unit = {
    val path = "a/src/main/scala/a/A.scala"
    testAsync(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(s"""/metals.json
                                  |{"a":{}}
                                  |/$path
                                  |${input.replaceAllLiterally("@@", "")}
                                  |""".stripMargin)
        _ <- server.didOpen(path)
        codeActions <- server.assertCodeAction(path, input, expectedActions)
        _ <- server.didSave(path) { _ =>
          if (selectedActionIndex >= codeActions.length) {
            fail(s"selectedActionIndex ($selectedActionIndex) is out of bounds")
          }
          client.applyCodeAction(codeActions(selectedActionIndex))
          server.toPath(path).readText
        }
        _ = assertNoDiff(server.bufferContents(path), expectedCode)
        _ = assertNoDiagnostics()
      } yield ()
    }
  }

}
