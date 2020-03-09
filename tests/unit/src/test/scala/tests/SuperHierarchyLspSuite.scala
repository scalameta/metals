package tests

import org.eclipse.lsp4j.Position

import scala.concurrent.Future

class SuperHierarchyLspSuite extends BaseLspSuite("supermethodhierarchy") {

  test("simple") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B extends A
        |trait C extends A { override def <<3>>xxx: String = "C -> " + super.xxx }
        |trait D extends C { override def <<4>>xxx: String = "D -> " + super.xxx }
        |""".stripMargin

    for {
      _ <- checkHierarchy(code, 4, List("a.C#xxx", "a.A#xxx"))
      _ <- checkHierarchy(code, 3, List("a.A#xxx"))
      _ <- checkNoHierarchy(code, 1)
    } yield ()
  }

  test("complex") {
    //TODO: Implement
  }

  test("with external dep") {
    //TODO: Implement
  }

  private def checkHierarchy(
      uri: String,
      pos: Int,
      expected: List[String]
  ): Future[Unit] = checkHierarchyInternal(uri, pos, expected)

  private def checkNoHierarchy(uri: String, pos: Int): Future[Unit] =
    checkHierarchyInternal(uri, pos, List())

  private def checkHierarchyInternal(
      code: String,
      pos: Int,
      expected: List[String]
  ): Future[Unit] = {
    val header = """
                   |/metals.json
                   |{
                   |  "a": { }
                   |}
                   |/a/src/main/scala/a/A.scala
                   |""".stripMargin

    cleanWorkspace()
    for {
      _ <- server.initialize(strip(header + code))
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
    } yield {
      val path = server.toPath("a/src/main/scala/a/A.scala").toURI.toString
      val context = parse(code)
      server.assertSuperMethodHierarchy(path, context(pos), expected)
    }
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
