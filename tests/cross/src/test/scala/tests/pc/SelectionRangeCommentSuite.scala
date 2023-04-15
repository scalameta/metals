package tests.pc

class SelectionRangeCommentSuite extends BaseSelectionRangeSuite {

// WIP! tests for selection expansion in comments not ready yet

  check(
    "match",
    """|object Main extends App {
       |  /*com@@ment*/
       |  val total = for {
       |    a <- Some(1)
       |    b <- Some(2)
       |  } yield a + b
       |}""".stripMargin,
    List(
      """|object Main extends App {
         |  /*>>region>>comment<<region<<*/
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b
         |}""".stripMargin,
      """|object Main extends App {
         |  >>region>>/*comment*/<<region<<
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b
         |}""".stripMargin,
    ),
  )

}
