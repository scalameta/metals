package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

object TypeDefinitionLspSuite
    extends BaseTypeDefinitionLspSuite
    with TestHovers {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      statistics = new StatisticsConfig("diagnostics")
    )

  check("multi-target")(
    query = """
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
              |    print/*.metals/readonly/scala/Unit.scala*/@@ln(name)
              |  }
              |}
              |/b/src/main/scala/a/B.scala
              |package a
              |object B {
              |  def main() = {
              |    println(A.name)
              |  }
              |}""".stripMargin
  )

  check("int")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |object Main {
              |  val ts/*.metals/readonly/scala/Int.scala*/@@t: Int = 2
              |}""".stripMargin
  )

  check("basic")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |
              |package a
              |
              |<<class Main(i: Int) {}>>
              |
              |object Main extends App {
              |  val te@@st = new Main(1)
              |}
        """.stripMargin
  )
  check("basicMultifile")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |package a
              |object Main {
              |  val ts@@t = new A(2)
              |}
              |/a/src/main/scala/a/Clazz.scala
              |package a
              |class <<A>>(i: Int){}
              |""".stripMargin
  )

  check("ext-library")(
    query = """|
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
               |    asse@@rt(condition)
               |  }
               |}
               |""".stripMargin,
    expectedLocs = List(
      ".metals/readonly/org/scalatest/compatible/Assertion.scala [28:6 -> 28:15]"
    )
  )

  check("method")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |
              |package a
              |
              |<<class Main(i: Int) {}>>
              |
              |object Main extends App {
              |  def foo(mn: Main): Unit = {
              |     println(m@@n)
              |  }
              |}
        """.stripMargin
  )

  check("method-definition")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |package a
              |class Main(i: Int) {}
              |object Main extends App {
              |  def foo(mn: Main): Unit = {
              |     println(mn)
              |  }
              |  fo/*.metals/readonly/scala/Unit.scala*/@@o(new Main(1))
              |}
        """.stripMargin
  )

  check("named-parameter")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |<<case class CClass(str: String) {}>>
              |
              |object Main {
              |  def tst(par: CClass): Unit = {}
              |
              |  tst(p@@ar = CClass("dads"))
              |}""".stripMargin
  )

  check("pattern-match")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |case class CClass(str: String) {}
              |
              |object Main {
              |  CClass("test") match {
              |    case CClass(st/*.metals/readonly/java/lang/String.java*/@@r) =>
              |       println(str)
              |    case _ =>
              |  }
              |}""".stripMargin
  )

  check("pattern-match-defined-unapply")(
    query = """
              |/metals.json
              |{"a": {}}
              |/a/src/main/scala/a/Main.scala
              |object CClass {
              | def unapply(c: CClass): Option[Int] = Some(1)
              |}
              |case class CClass(str: String)
              |
              |object Main {
              |  CClass("test") match {
              |    case CClass(st/*.metals/readonly/java/lang/String.java*/@@r) =>
              |       println(str)
              |    case _ =>
              |  }
              |}""".stripMargin
  )

}
