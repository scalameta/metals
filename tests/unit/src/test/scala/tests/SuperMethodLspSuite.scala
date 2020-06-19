package tests

import scala.concurrent.Future

import org.eclipse.lsp4j.Position

class SuperMethodLspSuite extends BaseLspSuite("gotosupermethod") {

  test("simple") {
    val code =
      """
        |package a
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B extends A { override def <<2->1>>xxx: String = "B -> " + super.xxx }
        |trait C extends A { override def <<3->1>>xxx: String = "C -> " + super.xxx }
        |trait D extends C { override def <<4->3>>xxx: String = "D -> " + super.xxx }
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("complex") {
    val code =
      """
        |package a
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1
        |trait B3 extends B2 { override def <<2->1>>xxx: String = "B3 -> " + super.xxx }
        |
        |trait C1 extends A
        |trait C2 extends C1 { override def <<3->1>>xxx: String = "C2 -> " + super.xxx }
        |trait C3 extends C2 { override def <<4->3>>xxx: String = "C3 -> " + super.xxx }
        |
        |trait D1 extends B1 { override def <<5->1>>xxx: String = "D1 -> " + super.xxx }
        |trait D2 extends B2
        |trait D3 extends B3
        |
        |class E1 extends A with C3 with B3 { override def <<6->2>>xxx: String = "E1 -> " + super.xxx }
        |class E2 extends A with C3 with B2 { override def <<7->4>>xxx: String = "E2 -> " + super.xxx }
        |class E3 extends A with C2 with B2 { override def <<8->3>>xxx: String = "E3 -> " + super.xxx }
        |class E4 extends A with C1 with B2 { override def <<9->1>>xxx: String = "E4 -> " + super.xxx }
        |
        |class E5 extends D1 with C2 with B2 { override def <<10->3>>xxx: String = "E5 -> " + super.xxx }
        |class E6 extends D1 with C1 with B2 { override def <<11->5>>xxx: String = "E6 -> " + super.xxx }
        |class E7 extends D3 with C2 with B3 { override def <<12->3>>xxx: String = "E7 -> " + super.xxx }
        |class E8 extends D3 with C1 with B3 { override def <<13->2>>xxx: String = "E8 -> " + super.xxx }
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("synthetic-methods") {
    val code =
      """
        |package a
        |case class A() {
        |  override def <<1->0>>toString: String = "A"
        |  override def <<2->0>>hashCode: Int = 1
        |  override def <<3->0>>clone(): Object = ???
        |}
        |""".stripMargin
    checkSuperMethod(code)

  }

  test("type-inheritance") {
    val code =
      """
        |package a
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2->1>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3->2>>xxx: String = "B3 -> " + super.xxx }
        |
        |object MidTypes {
        |  type Middle2 = B2
        |  type Middle3 = B3
        |
        |  class X1 extends Middle2 { override def <<4->2>>xxx: String = "X1 -> " + super.xxx }
        |  class X2 extends A with Middle2 { override def <<5->2>>xxx: String = "X2 -> " + super.xxx }
        |  class X3 extends Middle3 with Middle2 { override def <<6->3>>xxx: String = "X3 -> " + super.xxx }
        |}
        |
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("anonymous-class") {
    val code =
      """
        |package a
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2->1>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3->2>>xxx: String = "B3 -> " + super.xxx }
        |
        |object Anonymous {
        |  val c = new B2 { override def <<4->2>>xxx: String = "c -> " + super.xxx }
        |  val d = new B3 { override def <<5->3>>xxx: String = "d -> " + super.xxx }
        |  val e = new A with B2 with B3 { override def <<6->3>>xxx: String = "e -> " + super.xxx }
        |}
        |
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("object") {
    val code =
      """
        |package a
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2->1>>xxx: String = "B2 -> " + super.xxx }
        |
        |object O extends B2 {
        |  override def <<3->2>>xxx: String = "O -> " + super.xxx
        |}
        |
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("generic-types") {
    val code =
      """
        |package a
        |trait A
        |trait A1 extends A
        |case class AX() extends A1
        |
        |trait X[TP <: A] { def <<1->0>>fn(p: TP): String = "X" }
        |
        |trait X1[TP <: A] extends X[TP] { override def <<2->1>>fn(p: TP): String = s"X1 -> ${super.fn(p)}" }
        |trait X2[C] extends X[A] { override def <<3->1>>fn(p: A): String = s"X2[C] -> ${super.fn(p)}" }
        |trait X3 extends X[A1] { override def <<4->1>>fn(p: A1): String = s"X3 -> ${super.fn(p)}" }
        |
        |trait Y1[TP <: A] extends X1[TP] { override def <<5->2>>fn(p: TP): String = s"Y1[TP] -> ${super.fn(p)}" }
        |trait Y2[C] extends X1[A1] { override def <<6->2>>fn(p: A1): String = s"Y2[C] -> ${super.fn(p)}" }
        |trait Y3 extends X1[A] { override def <<6->2>>fn(p: A): String = s"Y3 -> ${super.fn(p)}" }
        |
        |trait Z2 extends X2[String] { override def <<7->3>>fn(p: A): String = s"Z2 -> ${super.fn(p)}" }
        |trait Z3 extends X3 { override def <<8->4>>fn(p: A1): String = s"Z3 -> ${super.fn(p)}" }
        |
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("matching-methods") {
    val code =
      """
        |package a
        |
        |trait X1
        |trait X2 extends X1
        |
        |trait A {
        |  def <<1->0>>a(a: String): String = "A.a(s)"
        |  def <<2->0>>a(a: Int): String = "A.a(i)"
        |  def <<3->0>>a(a: String, b: Int): String = "A.a(s,i)"
        |
        |  def <<4->0>>a(fn: Int => String): String = s"A.f(I->S)=${fn(1)}"
        |  def <<5->0>>a(x: Boolean): X1 = ???
        |}
        |
        |trait B extends A {
        |  override def <<6->1>>a(b: String): String = s"B.a(s) -> ${super.a(b)}"
        |  override def <<7->2>>a(b: Int): String = s"B.a(i) -> ${super.a(b)}"
        |  override def <<8->3>>a(x: String, y: Int) = s"B.a(s,i) -> ${super.a(x,y)}"
        |
        |  override def <<9->4>>a(fx: Int => String): String = s"B.f(I->S)=${fx(1)} -> ${super.a(fx)}"
        |  override def <<10->5>>a(x: Boolean): X2 = ???
        |}
        |
        |trait A1 { def <<20->0>>a(a: String, b: Boolean = true): String = ??? }
        |trait B1 extends A1 { override def <<21->20>>a(a: String, b: Boolean): String = ??? }
        |
        |trait A2 { def <<22->0>>a(a: String, b: Boolean): String = ??? }
        |trait B2 extends A2 { override def <<23->22>>a(a: String, b: Boolean = true): String = ??? }
        |
        |""".stripMargin
    checkSuperMethod(code)
  }

  test("multi-files") {
    val codeA =
      """
        |package a
        |
        |trait A { def <<1->0>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1 { override def <<2->1>>xxx: String = "B2 -> " + super.xxx }
        |trait B3 extends B2 { override def <<3->2>>xxx: String = "B3 -> " + super.xxx }
        |
        |""".stripMargin

    val codeB =
      """
        |package b
        |
        |trait C1 extends a.B1 { override def <<4->1>>xxx: String = "C1 -> " + super.xxx }
        |trait C2 extends a.B2 { override def <<5->2>>xxx: String = "C2 -> " + super.xxx }
        |trait C3 extends a.B3 { override def <<6->3>>xxx: String = "C3 -> " + super.xxx }
        |
        |""".stripMargin

    checkSuperMethodMulti(
      codeA,
      codeB
    )
  }

  test("jump-to-external-dependency") {
    val code =
      """
        |package a
        |import io.circe.Decoder
        |
        |trait CustomDecoder extends Decoder[String] {
        |  override def <<1->50>>apply(c: io.circe.HCursor): Decoder.Result[String] = ???
        |}
        |
        |""".stripMargin

    checkSuperMethod(code)
  }

  def checkSuperMethodMulti(
      codeA: String,
      codeB: String
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
      pathA = server.toPath("a/src/main/scala/a/A.scala").toURI.toString
      pathB = server.toPath("b/src/main/scala/b/B.scala").toURI.toString
      (contextA, assertsA) = parseWithUri(codeA, pathA)
      (contextB, assertsB) = parseWithUri(codeB, pathB)
      result <- server.assertGotoSuperMethod(
        assertsA ++ assertsB,
        contextA ++ contextB
      )

    } yield result
  }

  def checkSuperMethod(
      code: String
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

      path = server.toPath("a/src/main/scala/a/A.scala").toURI.toString

      // Checked manually it is actually there and operated under artificial ID link "50"
      externalDep = Map(
        50 -> (new Position(
          60,
          6
        ), workspace.toURI.toString + ".metals/readonly/io/circe/Decoder.scala")
      )

      (context, assertions) = parseWithUri(code, path)
      result <- server.assertGotoSuperMethod(assertions, context ++ externalDep)
    } yield result
  }

  private def strip(code: String): String = {
    code.replaceAll("\\<\\<\\S*\\>\\>", "")
  }

  private def parseWithUri(
      code: String,
      uri: String
  ): (Map[Int, (Position, String)], Map[Int, Option[Int]]) = {
    val (mapping, asserts) = parse(code)
    (mapping.mapValues((_, uri)), asserts)
  }

  private def parse(
      code: String
  ): (Map[Int, Position], Map[Int, Option[Int]]) = {
    var line: Int = 0
    var character: Int = 0
    var prev = '0'
    val result = scala.collection.mutable.Map[Int, Position]()
    val expected = scala.collection.mutable.Map[Int, Option[Int]]()
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
        val pair = context.split("->")
        val num: Int = Integer.valueOf(pair(0))
        if (pair(1) != "?") {
          val expectedInt = Integer.valueOf(pair(1)).intValue()
          expected(num) = if (expectedInt == 0) None else Some(expectedInt)
        }
        character = character - (buffer.length + 2)
        result(num) = new Position(line, character)
      }

      buffer += c
      prev = c
      character += 1
    }
    (result.toMap, expected.toMap)
  }
}
