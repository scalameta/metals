package tests

import org.eclipse.lsp4j.Position

import scala.concurrent.Future

class SuperMethodLspSuite extends BaseLspSuite("supermethod") {

  test("simple") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B extends A { override def <<2>>xxx: String = "B -> " + super.xxx }
        |trait C extends A { override def <<3>>xxx: String = "C -> " + super.xxx }
        |trait D extends C { override def <<4>>xxx: String = "D -> " + super.xxx }
        |""".stripMargin
    checkSuperMethod(
      code,
      Map(
        4 -> Some(3),
        3 -> Some(1),
        2 -> Some(1),
        1 -> None
      )
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
        |class E1 extends A with C3 with B3 { override def <<6>>xxx: String = "?" }
        |class E2 extends A with C3 with B2 { override def <<7>>xxx: String = "?" }
        |class E3 extends A with C2 with B2 { override def <<8>>xxx: String = "?" }
        |class E4 extends A with C1 with B2 { override def <<9>>xxx: String = "?" }
        |
        |class E5 extends D1 with C2 with B2 { override def <<10>>xxx: String = "?" }
        |class E6 extends D1 with C1 with B2 { override def <<11>>xxx: String = "?" }
        |class E7 extends D3 with C2 with B3 { override def <<12>>xxx: String = "?" }
        |class E8 extends D3 with C1 with B3 { override def <<13>>xxx: String = "?" }
        |""".stripMargin
    checkSuperMethod(
      code,
      Map(
        13 -> Some(2),
        12 -> Some(3),
        11 -> Some(5),
        10 -> Some(3),
        9 -> Some(1),
        8 -> Some(3),
        7 -> Some(4),
        6 -> Some(2),
        5 -> Some(1),
        4 -> Some(3),
        3 -> Some(1),
        2 -> Some(1),
        1 -> None
      )
    )
  }

  test("typeinheritance") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3>>xxx: String = "B3 -> " + super.xxx }
        |
        |object MidTypes {
        |  type Middle2 = B2
        |  type Middle3 = B3
        |
        |  class X1 extends Middle2 { override def <<4>>xxx: String = "X1 -> " + super.xxx }
        |  class X2 extends A with Middle2 { override def <<5>>xxx: String = "X2 -> " + super.xxx }
        |  class X3 extends Middle3 with Middle2 { override def <<6>>xxx: String = "X3 -> " + super.xxx }
        |}
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        6 -> Some(3),
        5 -> Some(2),
        4 -> Some(2),
        3 -> Some(2),
        2 -> Some(1),
        1 -> None
      )
    )
  }

  test("anonymousclass") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3>>xxx: String = "B3 -> " + super.xxx }
        |
        |object Anonymous {
        |  val c = new B2 { override def <<4>>xxx: String = "c -> " + super.xxx }
        |  val d = new B3 { override def <<5>>xxx: String = "d -> " + super.xxx }
        |  val e = new A with B2 with B3 { override def <<6>>xxx: String = "e -> " + super.xxx }
        |}
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        6 -> Some(3),
        5 -> Some(3),
        4 -> Some(2),
        3 -> Some(2),
        2 -> Some(1),
        1 -> None
      )
    )
  }

  test("object") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2>>xxx: String = "B2 -> " + super.xxx }
        |
        |object O extends B2 {
        |  override def <<3>>xxx: String = "O -> " + super.xxx
        |}
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        3 -> Some(2),
        2 -> Some(1),
        1 -> None
      )
    )
  }

  test("generic types") {
    val code =
      """
        |package a
        |trait A
        |trait A1 extends A
        |
        |trait X[TP <: A] { def <<1>>fn(p: TP): TP = ??? }
        |
        |trait X1[TP <: A] extends X[TP] { override def <<2>>fn(p: TP): TP = ??? }
        |trait X2[C] extends X[A] { override def <<3>>fn(p: A): A = ??? }
        |trait X3 extends X[A1] { override def <<4>>fn(p: A1): A1 = ??? }
        |
        |trait Y1[TP <: A] extends X1[TP] { override def <<5>>fn(p: TP): TP = ??? }
        |trait Y2[C] extends X1[A1] { override def <<6>>fn(p: A1): A1 = ??? }
        |trait Y3 extends X1[A] { override def <<7>>fn(p: A): A = ??? }
        |
        |trait Z2 extends X2[String] { override def <<8>>fn(p: A): A = ??? }
        |trait Z3 extends X3 { override def <<9>>fn(p: A1): A1 = ??? }
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        9 -> Some(4),
        8 -> Some(3),
//      7 -> Some(2),  TODO: Enable when https://github.com/scalameta/metals/issues/1474 is fixed
//      6 -> Some(2),  TODO: Enable when https://github.com/scalameta/metals/issues/1474 is fixed
        5 -> Some(2),
//      4 -> Some(1),  TODO: Enable when https://github.com/scalameta/metals/issues/1474 is fixed
//      3 -> Some(1),  TODO: Enable when https://github.com/scalameta/metals/issues/1474 is fixed
        2 -> Some(1),
        1 -> None
      )
    )
  }

  test("matching methods") {
    val code =
      """
        |package a
        |
        |trait X1
        |trait X2 extends X1
        |
        |trait A {
        |  def <<1>>a(a: String): String = "A.a(s)"
        |  def <<2>>a(a: Int): String = "A.a(i)"
        |  def <<3>>a(a: String, b: Int): String = "A.a(s,i)"
        |
        |  def <<4>>a(fn: Int => String): String = s"A.f(I->S)=${fn(1)}"
        |  def <<5>>a(x: Boolean): X1 = ???
        |}
        |
        |trait B extends A {
        |  override def <<11>>a(b: String): String = s"B.a(s) -> ${super.a(b)}"
        |  override def <<12>>a(b: Int): String = s"B.a(i) -> ${super.a(b)}"
        |  override def <<13>>a(x: String, y: Int) = s"B.a(s,i) -> ${super.a(x,y)}"
        |
        |  override def <<14>>a(fx: Int => String): String = s"B.f(I->S)=${fx(1)} -> ${super.a(fx)}"
        |  override def <<15>>a(x: Boolean): X2 = ???
        |}
        |
        |trait A1 { def <<20>>a(a: String, b: Boolean = true): String = ??? }
        |trait B1 extends A1 { override def <<21>>a(a: String, b: Boolean): String = ??? }
        |
        |trait A2 { def <<22>>a(a: String, b: Boolean): String = ??? }
        |trait B2 extends A2 { override def <<23>>a(a: String, b: Boolean = true): String = ??? }
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        23 -> Some(22),
        22 -> None,
        21 -> Some(20),
        20 -> None,
//      15 -> Some(5), TODO: Enable when https://github.com/scalameta/metals/issues/1474 is fixed
        14 -> Some(4),
        13 -> Some(3),
        12 -> Some(2),
        11 -> Some(1)
      )
    )
  }

  test("multi files") {
    val codeA =
      """
        |package a
        |
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3>>xxx: String = "B3 -> " + super.xxx }
        |
        |""".stripMargin

    val codeB =
      """
        |package b
        |
        |trait C1 extends a.B1 { override def <<4>>xxx: String = "C1 -> " + super.xxx }
        |trait C2 extends a.B2 { override def <<5>>xxx: String = "C2 -> " + super.xxx }
        |trait C3 extends a.B3 { override def <<6>>xxx: String = "C3 -> " + super.xxx }
        |
        |""".stripMargin

    checkSuperMethodMulti(
      codeA,
      codeB,
      Map(
        6 -> Some(3),
        5 -> Some(2),
        4 -> Some(1),
        3 -> Some(2),
        2 -> Some(1),
        1 -> None
      )
    )
  }

  //TODO: implement jumping to external source and test it here
  test("jump to external dependency".ignore) {
    val code =
      """
        |package a
        |import io.circe.Decoder
        |
        |trait CustomDecoder extends Decoder[String] {
        |  override def <<1>>apply(c: io.circe.HCursor): Decoder.Result[String] = ???
        |}
        |
        |""".stripMargin

    checkSuperMethod(
      code,
      Map(
        1 -> Some(1)
      )
    )
  }

  def checkSuperMethodMulti(
      codeA: String,
      codeB: String,
      assertions: Map[Int, Option[Int]]
  ): Future[Unit] = {
    val header = s"""
                    |/metals.json
                    |{
                    |  "a": { },
                    |  "b": {"dependsOn": ["a"]}
                    |}
                    |/a/src/main/scala/a/A.scala
                    |${codeA}
                    |/b/src/main/scala/b/B.scala
                    |${codeB}
                    |""".stripMargin

    cleanWorkspace()
    for {
      _ <- server.initialize(strip(header))
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
    } yield {
      val pathA = server.toPath("a/src/main/scala/a/A.scala").toURI.toString
      val pathB = server.toPath("b/src/main/scala/b/B.scala").toURI.toString
      val context = parseWithUri(codeA, pathA) ++ parseWithUri(codeB, pathB)
      for (check <- assertions) {
        println(s"CHECKING ${check}")
        server.assertSuperMethod(check._1, check._2, context)
      }
    }
  }

  def checkSuperMethod(
      code: String,
      assertions: Map[Int, Option[Int]]
  ): Future[Unit] = {
    val header = """
                   |/metals.json
                   |{
                   |  "a": {
                   |    "libraryDependencies": [
                   |      "io.circe::circe-generic:0.12.0"
                   |    ]
                   |  }
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
      val context = parseWithUri(code, path)
      for (check <- assertions) {
        println(s"CHECKING ${check}")
        server.assertSuperMethod(check._1, check._2, context)
      }
    }
  }

  private def strip(code: String): String = {
    code.replaceAll("\\<\\<\\S*\\>\\>", "")
  }

  private def parseWithUri(
      code: String,
      uri: String
  ): Map[Int, (Position, String)] = {
    parse(code).mapValues((_, uri))
  }

  private def parse(code: String): Map[Int, Position] = {
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
        val num: Int = Integer.valueOf(buffer.substring(1, buffer.length - 1))
        character = character - (buffer.length + 2)
        result(num) = new Position(line, character)
      }

      buffer += c
      prev = c
      character += 1
    }
    result.toMap
  }
}
