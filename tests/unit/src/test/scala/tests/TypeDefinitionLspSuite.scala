package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

class TypeDefinitionLspSuite extends BaseLspSuite("typeDefinition") {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      statistics = new StatisticsConfig("diagnostics")
    )

  test("type-definition") {
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
           |case class CClass(str: String)
           |object Extractor {
           | def unapply(c: CClass): Option[Int] = Some(1)
           |}
           |object Main extends App {
           |  val message = Message.message
           |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
           |  println(message)
           |  def extract(c: CClass) = c match {
           |    case CClass(foo) if foo != "bar" => foo
           |    case Extractor(foo) => foo
           |  }
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
           |  test("b") {
           |    val extracted = Main.extract(c = CClass(str = "foo"))
           |    val condition = extracted == "foo"
           |    assert(condition)
           |  }
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(server.workspaceTypeDefinitions, "")
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceTypeDefinitions,
        """|/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |case class CClass/*L3*/(str/*String.java*/: String/*Predef.scala*/)
           |object Extractor {
           | def unapply/*Option.scala*/(c/*L3*/: CClass/*L3*/): Option/*Option.scala*/[Int] = Some/*Option.scala*/(1)
           |}
           |object Main extends App/*App.scala*/ {
           |  val message/*String.java*/ = Message/*Message.java:1*/.message/*String.java*/
           |  new java.io.PrintStream/*PrintStream.java*/(new java.io.ByteArrayOutputStream/*ByteArrayOutputStream.java*/())
           |  println/*Unit.scala*/(message/*String.java*/)
           |  def extract(c/*L3*/: CClass/*L3*/) = c/*L3*/ match {
           |    case CClass(foo/*String.java*/) if foo/*String.java*/ !=/*Boolean.scala*/ "bar" => foo/*String.java*/
           |    case Extractor(foo/*Int.scala*/) => foo/*Int.scala*/
           |  }
           |}
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |import org.scalatest.FunSuite
           |object MainSuite extends FunSuite/*FunSuite.scala*/ {
           |  test/*Unit.scala*/("a") {
           |    val condition/*Boolean.scala*/ = Main/*Main.scala:7*/.message/*String.java*/.contains/*Boolean.scala*/("Hello")
           |    assert/*Assertion.scala*/(condition/*Boolean.scala*/)
           |  }
           |  test/*Unit.scala*/("b") {
           |    val extracted = Main/*Main.scala:7*/.extract(c/*Main.scala:3*/ = CClass/*Main.scala:3*/(str/*String.java*/ = "foo"))
           |    val condition/*Boolean.scala*/ = extracted ==/*Boolean.scala*/ "foo"
           |    assert/*Assertion.scala*/(condition/*Boolean.scala*/)
           |  }
           |}
           |""".stripMargin
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

}
