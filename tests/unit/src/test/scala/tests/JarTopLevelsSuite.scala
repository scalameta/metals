package tests

object JarTopLevelsSuite extends BaseSlowSuite("JarTopLevelsSuite") {
 testAsync("noCacheDefinition") {
    cleanDatabase()
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { },
           |  "b": {
           |    "libraryDependencies": [
           |      "org.scalatest::scalatest:3.0.5"
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
           |import org.scalatest.FunSuite
           |object MainSuite extends FunSuite {
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
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L4*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/("a") {
           |    val condition/*L6*/ = Main/*Main.scala:3*/.message/*Main.scala:4*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L6*/)
           |  }
           |}
           |""".stripMargin
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
           |  val helloMessage/*<no symbol>*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*<no symbol>*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L6*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/(testName/*<no symbol>*/) {
           |    val condition/*L8*/ = Main/*Main.scala:5*/.message/*<no symbol>*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L8*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("cachedefinition") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { },
           |  "b": {
           |    "libraryDependencies": [
           |      "org.scalatest::scalatest:3.0.5"
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
           |import org.scalatest.FunSuite
           |object MainSuite extends FunSuite {
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
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L4*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/("a") {
           |    val condition/*L6*/ = Main/*Main.scala:3*/.message/*Main.scala:4*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L6*/)
           |  }
           |}
           |""".stripMargin
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
           |  val helloMessage/*<no symbol>*/ = Message/*Message.java:1*/.message/*Message.java:2*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Predef.scala*/(message/*<no symbol>*/)
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |>>>>>>>/*<no symbol>*/
           |
           |package a
           |import java.util.concurrent.Future/*Future.java*/ // unused
           |import scala.util.Failure/*Try.scala*/ // unused
           |import org.scalatest.FunSuite/*FunSuite.scala*/
           |object MainSuite/*L6*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/(testName/*<no symbol>*/) {
           |    val condition/*L8*/ = Main/*Main.scala:5*/.message/*<no symbol>*/.contains/*String.java*/("Hello")
           |    assert/*Assertions.scala*/(condition/*L8*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }

}
