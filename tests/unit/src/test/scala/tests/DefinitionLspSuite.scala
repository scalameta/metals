package tests

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

class DefinitionLspSuite
    extends BaseLspSuite("definition")
    with ScriptsAssertions {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      statistics = new StatisticsConfig("diagnostics")
    )

  test("definition") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": { },
           |  "b": {
           |    "libraryDependencies": [
           |      "org.scalatest::scalatest:3.2.16"
           |    ],
           |    "dependsOn": [ "a" ]
           |  }
           |}
           |/a/src/main/java/a/Message.java
           |package a;
           |public class Message {
           |  public static String message = "Hello world!";
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |object Main extends App {
           |  val message = Message.message
           |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
           |  println(message)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |import org.scalatest.funsuite.AnyFunSuite
           |object MainSuite extends AnyFunSuite {
           |  test("a") {
           |    val condition = Main.message.contains("Hello")
           |    assert(condition)
           |  }
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(server.workspaceDefinitions, "")
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |object Main/*L3*/ extends App/*App.scala*/ {
           |  val message/*L4*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*L4*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.funsuite.AnyFunSuite/*AnyFunSuite.scala*/
           |object MainSuite/*L4*/ extends AnyFunSuite/*AnyFunSuite.scala*/ {
           |  test/*AnyFunSuiteLike.scala*/("a") {
           |    val condition/*L6*/ = Main/*Main.scala:3*/.message/*Main.scala:4*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L6*/)
           |  }
           |}
           |""".stripMargin,
      )
      _ <- server.didChange("b/src/main/scala/a/MainSuite.scala") { text =>
        ">>>>>>>\n\n" + text.replaceFirst("\"a\"", "testName")
      }
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        ">>>>>>>\n\n" + text.replaceFirst("message", "helloMessage")
      }
      _ = assertNoDiff(
        // Check that:
        // - navigation works for all unchanged identifiers, even if the buffer doesn't parse
        // - line numbers have shifted by 2 for both local and Main.scala references in MainSuite.scala
        // - old references to `message` don't resolve because it has been renamed to `helloMessage`
        // - new references to like `testName` don't resolve
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |object Main/*L5*/ extends App/*App.scala*/ {
           |  val helloMessage/*L6*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*<no symbol>*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.funsuite.AnyFunSuite/*AnyFunSuite.scala*/
           |object MainSuite/*L6*/ extends AnyFunSuite/*AnyFunSuite.scala*/ {
           |  test/*AnyFunSuiteLike.scala*/(testName/*<no symbol>*/) {
           |    val condition/*L8*/ = Main/*Main.scala:5*/.message/*<no symbol>*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L8*/)
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  // This test makes sure that textDocument/definition returns reference locations
  // instead of definition location if the symbol at the given text document position
  // represents a definition itself.
  // https://github.com/scalameta/metals/issues/755
  test("definition-fallback-to-show-usages") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {
          |    "dependsOn": [ "a" ]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val name = "John"
          |  def main() = {
          |    println(name)
          |  }
          |}
          |/b/src/main/scala/a/B.scala
          |package a
          |object B {
          |  def main() = {
          |    println(A.name)
          |  }
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/a/B.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |object A/*L1*/ {
           |  val name/*L2*/ = "John"
           |  def main/*L3*/() = {
           |    println/*Predef.scala*/(name/*L2*/)
           |  }
           |}
           |/b/src/main/scala/a/B.scala
           |package a
           |object B/*L1*/ {
           |  def main/*L2*/() = {
           |    println/*Predef.scala*/(A/*A.scala:1*/.name/*A.scala:2*/)
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("definition-case-class") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {
          |    "dependsOn": [ "a" ]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |case class A(name: String)
          |object A {
          |  val name = "John"
          |  val fun : () => Int = () => 1
          |}
          |/b/src/main/scala/a/B.scala
          |package a
          |object B {
          |  def main() = {
          |    println(A("John"))
          |    A.fun()
          |  }
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/a/B.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |
           |case class A/*L2*/(name/*L2*/: String/*Predef.scala*/)
           |object A/*L3*/ {
           |  val name/*L4*/ = "John"
           |  val fun/*L5*/ : () => Int/*Int.scala*/ = () => 1
           |}
           |/b/src/main/scala/a/B.scala
           |package a
           |object B/*L1*/ {
           |  def main/*L2*/() = {
           |    println/*Predef.scala*/(A/*A.scala:2*/("John"))
           |    A/*A.scala:3*/.fun/*A.scala:5*/()
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("stale") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/a/Main.scala
          |object Main {
          |  val x: Int = math.max(1, 2)
          |}
          |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |object Main/*L0*/ {
           |  val x/*L1*/: Int/*Int.scala*/ = math.max/*package.scala*/(1, 2)
           |}
        """.stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/a/Main.scala")(
        _.replace("max(1, 2)", "max")
      )
      _ <- server.didSave("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |object Main/*L0*/ {
           |  val x/*L1*/: Int/*Int.scala*/ = math.max/*package.scala*/
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("annotations") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "scalacOptions": ["-Ymacro-annotations"],
          |    "libraryDependencies": [
          |       "io.github.alexarchambault::data-class:0.2.5"
          |    ]
          |  }
          |}
          |/a/src/main/scala/a/User.scala
          |package a
          |import dataclass._
          |@data class User(name: String)
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  val user = User.apply("John")
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main/*L1*/ {
          |  val user/*L2*/ = User/*User.scala:2*/.apply/*User.scala:2*/("John")
          |}
          |""".stripMargin,
      )
    } yield ()
  }

  test("fallback-to-presentation-compiler") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  val name = "John"
          |  // println(name)
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didChange("a/src/main/scala/a/Main.scala")(
        _.replace("// ", "")
      )
      _ = assertNoDiff(
        server.workspaceDefinitions,
        // assert that definition of `name` and `assert` resolve even if they have not been saved.
        """|/a/src/main/scala/a/Main.scala
           |package a
           |object Main/*L1*/ {
           |  val name/*L2*/ = "John"
           |  println/*Predef.scala*/(name/*L2*/)
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("rambo", withoutVirtualDocs = true) {
    cleanDatabase()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${scala.meta.internal.metals.BuildInfo.scala213}"
           |  }
           |}
           |/Main.scala
           |object Main {
           |  println("hello!")
           |  val arr = Seq("").toArray
           |}
           |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("Main.scala")
      _ = server.workspaceDefinitions // trigger definition
      _ <- server.didOpen("scala/package.scala")
      _ <- server.didOpen("scala/collection/IterableOnce.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        "",
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("clashing-references") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {}
           |}
           |/a/src/main/scala/example/MainA.scala
           |package a
           |
           |class Main {
           |  val foo = new Foo
           |}
           |/a/src/main/scala/example/FooA.scala
           |package a
           |
           |class Foo
           |/b/src/main/scala/example/MainB.scala
           |package b
           |
           |class Main {
           |  val foo = new Foo
           |}
           |/b/src/main/scala/example/FooB.scala
           |package b
           |
           |class Foo
           |""".stripMargin
      )
      _ = server.didOpen("a/src/main/scala/example/MainA.scala")
      _ = server.didOpen("a/src/main/scala/example/FooA.scala")
      _ = server.didOpen("b/src/main/scala/example/MainB.scala")
      _ = server.didOpen("b/src/main/scala/example/FooB.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/example/FooA.scala
           |package a
           |
           |class Foo/*L2*/
           |/a/src/main/scala/example/MainA.scala
           |package a
           |
           |class Main/*L2*/ {
           |  val foo/*L3*/ = new Foo/*FooA.scala:2*/
           |}
           |/b/src/main/scala/example/FooB.scala
           |package b
           |
           |class Foo/*L2*/
           |
           |/b/src/main/scala/example/MainB.scala
           |package b
           |
           |class Main/*L2*/ {
           |  val foo/*L3*/ = new Foo/*FooB.scala:2*/
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("jar-with-plus", withoutVirtualDocs = true) {
    import scala.meta.internal.metals.MetalsEnrichments._
    val testCase =
      """|package a
         |
         |import com.thoughtworks.dsl.D@@sl
         |class Main {
         |  val foo = ""
         |}""".stripMargin
    val fileContents = testCase.replace("@@", "")
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { 
           |    "libraryDependencies" : ["com.thoughtworks.dsl::dsl:2.0.0-M0+1-b691cde8"] 
           |  }
           |}
           |/a/src/main/scala/example/MainA.scala
           |$fileContents
           |""".stripMargin
      )
      _ = server.didOpen("a/src/main/scala/example/MainA.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/example/MainA.scala
           |package a
           |
           |import com.thoughtworks.dsl.Dsl/*Dsl.scala*/
           |class Main/*L3*/ {
           |  val foo/*L4*/ = ""
           |}
           |""".stripMargin,
      )
      definition <- server.definition(
        "a/src/main/scala/example/MainA.scala",
        testCase,
      )
      _ = assert(definition.nonEmpty, "Definition for Dsl class not found")
      mainDefUri = definition.head.getUri()
      contents <-
        // jar is returned if virtual files are supported
        if (mainDefUri.startsWith("jar"))
          server.executeDecodeFileCommand(mainDefUri).map { result =>
            assert(
              result.value != null,
              "No file contents returned for Dsl.scala",
            )
            result.value

          }
        else Future.successful(mainDefUri.toAbsolutePath.readText)
    } yield {
      assertContains(contents, "trait Dsl[-Keyword, Domain, +Value]")
    }
  }

  test("init-args") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/a/Main.scala
          |class A(
          |  a: Int, 
          |  b: Int
          |) {}
          |object Main {
          |  val aa = new A(a = 1, b = 2)
          |}
          |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |class A/*L0*/(
           |  a/*L1*/: Int/*Int.scala*/, 
           |  b/*L2*/: Int/*Int.scala*/
           |) {}
           |object Main/*L4*/ {
           |  val aa/*L5*/ = new A/*L0*/(a/*L1*/ = 1, b/*L2*/ = 2)
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("scaladoc-definition") {
    val testCase =
      """|package a
         |
         |object O {
         |  /**
         |   * Returns a [[scala.Do@@uble]] representing yada yada yada...
         |   */
         |  def f: Double = ???
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |${testCase.replace("@@", "")}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      locations <- server.definition(
        "a/src/main/scala/a/Main.scala",
        testCase,
      )
      _ = assert(locations.nonEmpty)
      _ = assert(locations.head.getUri().endsWith("scala/Double.scala"))
    } yield ()
  }

  test("scaladoc-definition-this") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object O {
           |  class A {
           |    /**
           |     * Calls [[this.g]]
           |     */
           |    def f: Int = g
           |    def g: Int = ???
           |  }
           |}
           |""".stripMargin
      )
      _ = assertDefinitionAtLocation(
        "a/src/main/scala/a/Main.scala",
        "this@@.g",
        "a/src/main/scala/a/Main.scala",
        expectedLine = 8,
      )
    } yield ()
  }

  test("scaladoc-find-all-overridden-methods") {
    val testCase =
      """|package a.internal
         |
         |object O {
         |  class A {
         |    /**
         |     * Calls [[fo@@o]]
         |     */
         |    def f: Int = g
         |    def foo: Int = ???
         |    def foo(i: Int): Int = ???
         |    def foo(str: String, i: Int): Int = ???
         |  }
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |${testCase.replace("@@", "")}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      locations <- server.definition(
        "a/src/main/scala/a/Main.scala",
        testCase,
      )
      _ = assert(locations.length == 3)
      _ = assert(locations.forall(_.getUri().endsWith("a/Main.scala")))
      _ = assertEquals(
        locations.map(_.getRange().getStart().getLine()),
        List(8, 9, 10),
      )
    } yield ()
  }

  test("nested-jars") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { 
           |      "libraryDependencies": [
           |        "com.daml:bindings-rxjava:2.0.0"
           |      ]
           |    }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |import com.daml.ledger.rxjava.DamlLedgerClient
           |
           |object O {
           |  val k: DamlLedgerClient = ???
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |package a
           |import com.daml.ledger.rxjava.DamlLedgerClient/*DamlLedgerClient.java*/
           |
           |object O/*L3*/ {
           |  val k/*L4*/: DamlLedgerClient/*DamlLedgerClient.java*/ = ???/*Predef.scala*/
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  // Marked as flaky from CI failure:
  //   2025.09.17 18:12:25 INFO  tests.TestingClient#metalsExecuteClientCommand metals-model-refresh
  //   Sep 17, 2025 6:12:25 PM scala.meta.internal.pc.ScalaPresentationCompiler newCompiler
  //   INFO: [file:/home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol/a/?id=a]: sourcepath: /home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol/a/src/main/scala-3:/home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol/a/src/main/scala:/home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol/a/src/main/java
  //   bad option: '-Xsemanticdb'
  //   Sep 17, 2025 6:12:25 PM scala.meta.internal.pc.ScalaPresentationCompiler newCompiler
  //   WARNING: Unknown compiler options: -Xsemanticdb, -sourceroot, /home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol
  //   Sep 17, 2025 6:12:25 PM scala.meta.internal.metals.StdReporter $anonfun$create$1
  //   SEVERE: empty definition using pc, found symbol in pc:  (full report at: /home/runner/work/metals/metals/tests/unit/target/e2e/definition/go-to-reexported-symbol/.metals/.reports/metals-full/2025-09-17/r_empty-definition_(a)_18-12-25-613.md)
  //   2025.09.17 18:12:25 WARN  The test failed, printing the traces:
  //   [error] ==> X tests.DefinitionLspSuite.go-to-reexported-symbol  32.803s munit.FailException: tests/unit/src/test/scala/tests/DefinitionLspSuite.scala:724 assertion failed
  //   [error] 723:      _ = assert(locations.length == 1)
  //   [error] 724:      _ = assert(locations.forall(_.getUri().endsWith("a/Other.scala")))
  //   [error] 725:      _ = assertEquals(
  //   [error]     at munit.Assertions.fail(Assertions.scala:256)
  test("go-to-reexported-symbol".flaky) {
    val testCase =
      """|package a
         |class Test extends A {
         |  assert("Hello".fo@@o == "HelloFoo")
         |}
         |
         |trait A {
         |  export B.*
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { "scalaVersion": "3.4.1" }
           |}
           |/a/src/main/scala/a/Main.scala
           |${testCase.replace("@@", "")}
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object B {
           |  extension (value: String) def foo: String = s"$${value}Foo"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Other.scala")
      locations <- server.definition(
        "a/src/main/scala/a/Main.scala",
        testCase,
      )
      _ = assert(locations.length == 1)
      _ = assert(locations.forall(_.getUri().endsWith("a/Other.scala")))
      _ = assertEquals(
        locations.map(_.getRange().getStart().getLine()),
        List(3),
      )
    } yield ()
  }

}
