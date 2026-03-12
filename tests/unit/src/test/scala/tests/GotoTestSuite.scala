package tests

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.ServerCommands

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

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
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath("a/src/main/scala/a/MyClass.scala").toURI.toString
          ),
          new Position(1, 6),
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
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath("a/src/main/scala/a/FooSuite.scala").toURI.toString
          ),
          new Position(1, 6),
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
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath("a/src/main/scala/a/Lonely.scala").toURI.toString
          ),
          new Position(1, 6),
        ),
      )
      _ = assert(
        !client.workspaceClientCommands.contains("metals-goto-location"),
        s"Should not navigate when no test class exists, got: ${client.workspaceClientCommands}",
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
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath("a/src/main/scala/a/Widget.scala").toURI.toString
          ),
          new Position(1, 6),
        ),
      )
      _ = assert(
        client.workspaceClientCommands.contains("metals-goto-location"),
        s"Expected goto-location command for Spec suffix, got: ${client.workspaceClientCommands}",
      )
    } yield ()
  }
}
