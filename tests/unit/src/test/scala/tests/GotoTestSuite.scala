package tests

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.ServerCommands

class GotoTestSuite extends BaseLspSuite("goto-test") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("source-to-test") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/MyClass.scala
           |package a
           |class MyClass {
           |  def hello: String = "hello"
           |}
           |/a/src/main/scala/a/MyClassTest.scala
           |package a
           |class MyClassTest {
           |  def testHello: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/MyClass.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/MyClass.scala",
          "class My@@Class {",
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Expected goto-location command, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }

  test("test-to-source") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo {
           |  def hello: String = "hello"
           |}
           |/a/src/main/scala/a/FooSuite.scala
           |package a
           |class FooSuite {
           |  def testHello: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/FooSuite.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/FooSuite.scala",
          "class Foo@@Suite {",
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Expected goto-location command, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }

  test("no-matching-class") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Lonely.scala
           |package a
           |class Lonely {
           |  def hello: String = "hello"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Lonely.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/Lonely.scala",
          "class Lon@@ely {",
        ),
      )
      _ = assert(
        !client.workspaceClientCommands.contains("metals-goto-location"),
        s"Should not navigate when no test class exists, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }

  test("inner-class-goes-to-enclosing-test") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Container.scala
           |package a
           |class Container {
           |  class Inner {
           |    def doStuff: String = "stuff"
           |  }
           |}
           |/a/src/main/scala/a/ContainerTest.scala
           |package a
           |class ContainerTest {
           |  def testContainer: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Container.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/Container.scala",
          "class In@@ner {",
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Inner class should navigate to enclosing class's test, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }

  test("code-lens-only-on-top-level-class") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Outer.scala
           |package a
           |class Outer {
           |  class Nested {
           |    def work: String = "work"
           |  }
           |}
           |/a/src/main/scala/a/OuterTest.scala
           |package a
           |class OuterTest {
           |  def testOuter: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Outer.scala")
      lenses <- server.codeLenses("a/src/main/scala/a/Outer.scala")
      gotoTestLenses = lenses.filter { lens =>
        val title = lens.getCommand.getTitle
        title == "Go to Test" || title == "Go to Test Subject"
      }
      _ = assert(
        gotoTestLenses.size == 1,
        s"Expected exactly one Go to Test lens (for Outer only, not Nested), got ${gotoTestLenses.size}",
      )
      _ = assertEquals(
        gotoTestLenses.head.getRange.getStart.getLine,
        1,
        "Go to Test lens should be on the Outer class (line 1)",
      )
    } yield ()
  }

  test("object-to-test") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Helpers.scala
           |package a
           |object Helpers {
           |  def greet: String = "hi"
           |}
           |/a/src/main/scala/a/HelpersTest.scala
           |package a
           |class HelpersTest {
           |  def testGreet: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Helpers.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/Helpers.scala",
          "object Hel@@pers {",
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Expected goto-location command for object, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }

  test("spec-suffix") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Widget.scala
           |package a
           |class Widget {
           |  def render: String = "widget"
           |}
           |/a/src/main/scala/a/WidgetSpec.scala
           |package a
           |class WidgetSpec {
           |  def testRender: Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Widget.scala")
      _ <- server.executeCommand(
        ServerCommands.GotoTest,
        server.textDocumentPositionParams(
          "a/src/main/scala/a/Widget.scala",
          "class Wid@@get {",
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Expected goto-location command for Spec suffix, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }
}
