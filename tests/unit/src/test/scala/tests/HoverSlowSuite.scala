package tests

object HoverSlowSuite extends BaseSlowSuite("hover") with TestHovers {

  testAsync("basic") {
    for {
      _ <- server.initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """override def head: Int""".hover
      )
    } yield ()
  }

}
