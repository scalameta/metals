package tests

class FoldingRangeLspSuite extends BaseLspSuite("foldingRange") {
  test("parse-error") {
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object Main {
           |  def foo = {
           |    ???
           |    ???
           |    ???
           |  }
           |
           |  val justAPadding = ???
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.assertFolded(
        "a/src/main/scala/a/Main.scala",
        """object Main >>region>>{
          |  def foo = >>region>>{
          |    ???
          |    ???
          |    ???
          |  }<<region<<
          |
          |  val justAPadding = ???
          |}<<region<<""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        "__" + "\n\n" + text
      }
      _ <- server.assertFolded(
        "a/src/main/scala/a/Main.scala",
        """__
          |
          |object Main >>region>>{
          |  def foo = >>region>>{
          |    ???
          |    ???
          |    ???
          |  }<<region<<
          |
          |  val justAPadding = ???
          |}<<region<<""".stripMargin,
      )
    } yield ()
  }
}
