package tests

import scala.concurrent.Future
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.MtagsEnrichments._
import org.eclipse.{lsp4j => l}

object TypeDefinitionLspSuite
    extends BaseLspSuite("typeDefinition")
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

  def check(
      name: String
  )(query: String, expectedLocs: List[String] = Nil): Unit = {
    testAsync(name) {
      cleanWorkspace()
      val code =
        query
          .replaceAll("(@@)?(<<)?(>>)?", "")
          .replaceAll("""\/\*[^\*]+\*\/""", "")
      val files = query.lines
        .filter(_.startsWith("/"))
        .filter(_.filter(!_.isWhitespace) != "/metals.json")
        .map(_.stripPrefix("/"))

      for {
        _ <- server.initialize(
          s"""${code.trim}""".stripMargin
        )
        _ <- Future.sequence(files.map(server.didOpen))
        expected = expectedLocs.flatMap(
          TestingServer.locationFromString(_, workspace)
        )
        _ <- server.assertTypeDefinition(
          queryStr = query,
          expectedLocs = expected,
          root = workspace
        )
        _ = assertNoDiagnostics()
        _ = if (!server.server.isInitialized.get())
          fail("Build tool not initialized")
      } yield ()
    }
  }

  def prepareDefinition(original: String): String =
    original.replaceAll("(<<)?(>>)?", "")

  def locationsToCode(
      code: String,
      uri: String,
      offsetRange: l.Range,
      locations: List[l.Location]
  ): String = {
    val edits = locations.flatMap { loc =>
      {
        val location = new l.Location(
          workspace.toURI.relativize(new java.net.URI(loc.getUri)).toString,
          loc.getRange
        )
        if (location.getUri == uri) {
          List(
            new l.TextEdit(
              new l.Range(
                location.getRange.getStart,
                location.getRange.getStart
              ),
              "<<"
            ),
            new l.TextEdit(
              new l.Range(
                location.getRange.getEnd,
                location.getRange.getEnd
              ),
              ">>"
            )
          )
        } else {
          val filename = location.getUri
          val comment = s"/*$filename*/"
          if (code.contains(comment)) {
            Nil
          } else {
            List(new l.TextEdit(offsetRange, comment))
          }
        }
      }
    }
    TextEdits.applyEdits(code, edits)
  }

  def obtainedAndExpected(original: String, uri: String): Future[String] = {
    val code = prepareDefinition(original)
    val offset = code.indexOf("@@")
    if (offset < 0) fail("@@ missing")

    val offsetRange = Position.Range(Input.String(code), offset, offset).toLSP
    val locationsF = server.typeDefinition(uri, code)
    locationsF.map(l => locationsToCode(code, uri, offsetRange, l))
  }

  def checkTypeDefinition(
      query: String,
      name: String = "Main.scala"
  ): Future[Unit] = {
    val obtainedF =
      obtainedAndExpected(query, workspace.toURI.resolve(name).toString)

    val expected = query
    obtainedF.map(o => assertNoDiff(o, expected))
  }

}
