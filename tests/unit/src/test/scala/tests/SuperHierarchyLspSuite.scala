package tests

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions

import org.eclipse.lsp4j.Position

class SuperHierarchyLspSuite extends BaseLspSuite("super-method-hierarchy") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("simple") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B extends A
        |trait C extends A { override def <<3>>xxx: String = "C -> " + super.xxx }
        |trait D extends C { override def <<4>>xxx: String = "D -> " + super.xxx }
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        3 -> List("a.A#xxx"),
        4 -> List("a.C#xxx", "a.A#xxx"),
      ),
    )
  }

  test("complex") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1
        |trait B3 extends B2 { override def <<2>>xxx: String = "B3 -> " + super.xxx }
        |
        |trait C1 extends A
        |trait C2 extends C1 { override def <<3>>xxx: String = "C2 -> " + super.xxx }
        |trait C3 extends C2 { override def <<4>>xxx: String = "C3 -> " + super.xxx }
        |
        |trait D1 extends B1 { override def <<5>>xxx: String = "D1 -> " + super.xxx }
        |trait D2 extends B2
        |trait D3 extends B3
        |
        |class E1 extends A with C3 with B3 { override def <<6>>xxx: String = "E1 -> " + super.xxx }
        |class E2 extends A with C3 with B2 { override def <<7>>xxx: String = "E2 -> " + super.xxx }
        |class E3 extends A with C2 with B2 { override def <<8>>xxx: String = "E3 -> " + super.xxx }
        |class E4 extends A with C1 with B2 { override def <<9>>xxx: String = "E4 -> " + super.xxx }
        |
        |class E5 extends D1 with C2 with B2 { override def <<10>>xxx: String = "E5 -> " + super.xxx }
        |class E6 extends D1 with C1 with B2 { override def <<11>>xxx: String = "E6 -> " + super.xxx }
        |class E7 extends D3 with C2 with B3 { override def <<12>>xxx: String = "E7 -> " + super.xxx }
        |class E8 extends D3 with C1 with B3 { override def <<13>>xxx: String = "E8 -> " + super.xxx }
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        2 -> List("a.A#xxx"),
        3 -> List("a.A#xxx"),
        4 -> List("a.C2#xxx", "a.A#xxx"),
        // D1
        5 -> List("a.A#xxx"),
        // E1-E4
        6 -> List("a.B3#xxx", "a.C3#xxx", "a.C2#xxx", "a.A#xxx"),
        7 -> List("a.C3#xxx", "a.C2#xxx", "a.A#xxx"),
        8 -> List("a.C2#xxx", "a.A#xxx"),
        9 -> List("a.A#xxx"),
        // E5-E8
        10 -> List("a.C2#xxx", "a.D1#xxx", "a.A#xxx"),
        11 -> List("a.D1#xxx", "a.A#xxx"),
        12 -> List("a.C2#xxx", "a.B3#xxx", "a.A#xxx"),
        13 -> List("a.B3#xxx", "a.A#xxx"),
      ),
    )
  }

  test("with external dep", withoutVirtualDocs = true) {
    val code =
      """
        |package a
        |import java.nio.file.FileSystem
        |import java.nio.file.FileStore
        |
        |trait CustomFileSystem extends FileSystem {
        |  override def <<1>>close(): Unit = ???
        |
        |  override def <<2>>getFileStores(): java.lang.Iterable[FileStore] = ???
        |}
        |
        |
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        1 -> List(
          "java.nio.file.FileSystem#close",
          "java.io.Closeable#close",
          "java.lang.AutoCloseable#close",
        ),
        2 -> List("java.nio.file.FileSystem#getFileStores"),
      ),
    )
  }

  private def checkHierarchy(
      code: String,
      expectations: Map[Int, List[String]],
  ): Future[Unit] = {
    val header = """
                   |/metals.json
                   |{
                   |  "a": {
                   |    "libraryDependencies": []
                   |  }
                   |}
                   |/a/src/main/scala/a/A.scala
                   |""".stripMargin

    cleanWorkspace()
    for {
      _ <- initialize(strip(header + code))
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()

      path = server.toPath("a/src/main/scala/a/A.scala").toURI.toString
      context = parse(code)
      result <- server.assertSuperMethodHierarchy(
        path,
        expectations.toList,
        context,
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
