package tests

import scala.concurrent.Future
import scala.meta as m

import scala.meta.internal.metals.BuildInfo as V
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments.*

import org.eclipse.lsp4j.Position

abstract class BaseSuperHierarchySuite(name: String)
    extends BaseLspSuite(name) {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  protected val libraryMembers: String =
    """
      |package a
      |class B extends Ordering[Int] { override def compare(x: Int, y: Int): Int = 0 }
      |object Main {
      |  val xs = List(1).<<1>>map(_ + 1)
      |  val n = "a".<<2>>length
      |  val s = List(1).<<3>>toString
      |  val c = new B().<<4>>compare(1, 2)
      |  val h = "a".<<5>>hashCode
      |  val sb = new java.lang.StringBuilder().<<6>>append('c')
      |}
      |""".stripMargin

  protected val libraryOverloads: String =
    """|package a
       |object Main {
       |  val b = scala.collection.mutable.ArrayBuffer(1, 2, 3)
       |  val x = b.<<1>>remove(0)
       |  val u = b.<<2>>remove(0, 1)
       |}
       |""".stripMargin

  protected def checkDependencySource(
      scalaVersion: String,
      expected: List[String],
  ): Future[Unit] = {
    val main = "a/src/main/scala/a/A.scala"
    val code =
      """|package a
         |object Main { val xs = List(1).ma@@p(_ + 1) }
         |""".stripMargin
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{ "a": { "scalaVersion": "$scalaVersion" } }
            |/$main
            |${code.replace("@@", "")}""".stripMargin
      )
      _ <- server.didOpen(main)
      _ <- server.definition(main, code, workspace)
      dependency = "scala/collection/immutable/List.scala"
      _ <- server.didOpen(dependency)
      path = server.toPath(dependency)
      text = path.readText
      offset = text.indexOf("(b.toList, these)") + "(b.".length
      position = m.Position.Range(m.Input.String(text), offset, offset)
      result <- server.assertSuperMethodHierarchy(
        path.toURI.toString,
        List(1 -> expected),
        Map(1 -> position.toLsp.getStart),
      )
    } yield result
  }

  protected def checkHierarchy(
      code: String,
      expectations: Map[Int, List[String]],
      scalaVersion: String = V.scala213,
      file: String = "a/src/main/scala/a/A.scala",
      otherFiles: String = "",
      javaOnly: Boolean = false,
      javacOptions: List[String] = Nil,
      rawSymbols: Boolean = false,
  ): Future[Unit] = {
    // circe 0.12.0 has no Scala 3 build
    val dependency =
      if (scalaVersion.startsWith("3.")) ""
      else "\"io.circe::circe-generic:0.12.0\""
    val javac = javacOptions.map(option => s"\"$option\"").mkString(", ")
    val header = s"""
                    |/metals.json
                    |{
                    |  "a": {
                    |    "scalaVersion": "$scalaVersion",
                    |    "javaOnly": $javaOnly,
                    |    "javacOptions": [$javac],
                    |    "libraryDependencies": [
                    |      $dependency
                    |    ]
                    |  }
                    |}
                    |$otherFiles/$file
                    |""".stripMargin

    cleanWorkspace()
    for {
      _ <- initialize(strip(header + code))
      _ <- server.didOpen(file)
      _ = assertNoDiagnostics()

      path = server.toPath(file).toURI.toString
      context = parse(code)
      result <- server.assertSuperMethodHierarchy(
        path,
        expectations.toList,
        context,
        rawSymbols,
      )
    } yield result
  }

  private def parse(
      code: String
  ): Map[Int, Position] = {
    var line: Int = 0
    var character: Int = 0
    var prev = '0'
    val result = scala.collection.mutable.Map[Int, Position]()
    var buffer = ""
    for (c <- code) {
      if (c == '\n') {
        line += 1
        character = 0
      }

      if (c == '<' && prev == '<') {
        buffer = ""
      }
      if (c == '>' && prev == '>') {
        val context = buffer.substring(1, buffer.length - 1)
        val num: Int = Integer.valueOf(context)
        character = character - (buffer.length + 2)
        result(num) = new Position(line, character)
      }

      buffer += c
      prev = c
      character += 1
    }
    result.toMap
  }

  private def strip(code: String): String = {
    code.replaceAll("\\<\\<\\S*\\>\\>", "")
  }

}
