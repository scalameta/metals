package tests.pc

class SelectionRangeCommentSuite extends BaseSelectionRangeSuite {

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
         |  >>region>>/*comment*/<<region<<
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b
         |}""".stripMargin
    )
  )

}
