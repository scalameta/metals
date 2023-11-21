package tests.pc

class SelectionRangeCommentSuite extends BaseSelectionRangeSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

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
         |}""".stripMargin,
      """|object Main extends >>region>>App {
         |  /*comment*/
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b<<region<<
         |}
         |""".stripMargin
    )
  )

}
