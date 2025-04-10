package tests.pc

class SelectionRangeSuite extends BaseSelectionRangeSuite {

  check(
    "match",
    """|object Main extends App {
       |  Option("chris") match {
       |    case Some(n@@ame) => println("Hello! " + name)
       |    case None =>
       |  }
       |}""".stripMargin,
    List(
      """|object Main extends App {
         |  Option("chris") match {
         |    case Some(>>region>>name<<region<<) => println("Hello! " + name)
         |    case None =>
         |  }
         |}""".stripMargin,
      """|object Main extends App {
         |  Option("chris") match {
         |    case >>region>>Some(name)<<region<< => println("Hello! " + name)
         |    case None =>
         |  }
         |}""".stripMargin,
      """|object Main extends App {
         |  Option("chris") match {
         |    case >>region>>Some(name) => println("Hello! " + name)<<region<<
         |    case None =>
         |  }
         |}""".stripMargin,
      """|object Main extends App {
         |  >>region>>Option("chris") match {
         |    case Some(name) => println("Hello! " + name)
         |    case None =>
         |  }<<region<<
         |}""".stripMargin,
      """|object Main >>region>>extends App {
         |  Option("chris") match {
         |    case Some(name) => println("Hello! " + name)
         |    case None =>
         |  }
         |}<<region<<""".stripMargin,
      """|>>region>>object Main extends App {
         |  Option("chris") match {
         |    case Some(name) => println("Hello! " + name)
         |    case None =>
         |  }
         |}<<region<<""".stripMargin
    ),
    Map(
      "3" ->
        List(
          """|object Main extends App {
             |  Option("chris") match {
             |    case Some(>>region>>name<<region<<) => println("Hello! " + name)
             |    case None =>
             |  }
             |}""".stripMargin,
          """|object Main extends App {
             |  Option("chris") match {
             |    case >>region>>Some(name)<<region<< => println("Hello! " + name)
             |    case None =>
             |  }
             |}""".stripMargin,
          """|object Main extends App {
             |  Option("chris") match {
             |    >>region>>case Some(name) => println("Hello! " + name)<<region<<
             |    case None =>
             |  }
             |}""".stripMargin,
          """|object Main extends App {
             |  >>region>>Option("chris") match {
             |    case Some(name) => println("Hello! " + name)
             |    case None =>
             |  }<<region<<
             |}""".stripMargin,
          """|object Main extends >>region>>App {
             |  Option("chris") match {
             |    case Some(name) => println("Hello! " + name)
             |    case None =>
             |  }<<region<<
             |}""".stripMargin,
          """|>>region>>object Main extends App {
             |  Option("chris") match {
             |    case Some(name) => println("Hello! " + name)
             |    case None =>
             |  }
             |}<<region<<""".stripMargin
        )
    )
  )

  check(
    "for",
    """|object Main extends App {
       |  val total = for {
       |    a <- S@@ome(1)
       |    b <- Some(2)
       |  } yield a + b
       |}""".stripMargin,
    List(
      """|object Main extends App {
         |  val total = for {
         |    a <- >>region>>Some<<region<<(1)
         |    b <- Some(2)
         |  } yield a + b
         |}""".stripMargin,
      """|object Main extends App {
         |  val total = for {
         |    a <- >>region>>Some(1)<<region<<
         |    b <- Some(2)
         |  } yield a + b
         |}""".stripMargin,
      """|object Main extends App {
         |  val total = >>region>>for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b<<region<<
         |}""".stripMargin,
      """|object Main extends App {
         |  >>region>>val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b<<region<<
         |}""".stripMargin,
      """|object Main >>region>>extends App {
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b
         |}<<region<<""".stripMargin,
      """|>>region>>object Main extends App {
         |  val total = for {
         |    a <- Some(1)
         |    b <- Some(2)
         |  } yield a + b
         |}<<region<<""".stripMargin
    ),
    Map(
      "3" -> List(
        """|object Main extends App {
           |  val total = for {
           |    a <- >>region>>Some<<region<<(1)
           |    b <- Some(2)
           |  } yield a + b
           |}""".stripMargin,
        """|object Main extends App {
           |  val total = for {
           |    a <- >>region>>Some(1)<<region<<
           |    b <- Some(2)
           |  } yield a + b
           |}""".stripMargin,
        """|object Main extends App {
           |  val total = >>region>>for {
           |    a <- Some(1)
           |    b <- Some(2)
           |  } yield a + b<<region<<
           |}""".stripMargin,
        """|object Main extends App {
           |  >>region>>val total = for {
           |    a <- Some(1)
           |    b <- Some(2)
           |  } yield a + b<<region<<
           |}""".stripMargin,
        """|object Main extends >>region>>App {
           |  val total = for {
           |    a <- Some(1)
           |    b <- Some(2)
           |  } yield a + b<<region<<
           |}""".stripMargin,
        """|>>region>>object Main extends App {
           |  val total = for {
           |    a <- Some(1)
           |    b <- Some(2)
           |  } yield a + b
           |}<<region<<""".stripMargin
      )
    )
  )

  check(
    "def - braceless".tag(IgnoreScala2),
    """object Main extends App :
      |  def foo(hi: Int, b@@: Int, c:Int) = ???  """.stripMargin,
    List(
      """object Main extends App :
        |  def foo(hi: Int, >>region>>b: Int<<region<<, c:Int) = ??? """.stripMargin,
      """object Main extends App :
        |  def foo(>>region>>hi: Int, b: Int, c:Int<<region<<) = ??? """.stripMargin,
      """object Main extends App :
        |  >>region>>def foo(hi: Int, b: Int, c:Int) = ???<<region<< """.stripMargin,
      """object Main extends >>region>>App :
        |  def foo(hi: Int, b: Int, c:Int) = ???<<region<<""".stripMargin,
      """>>region>>object Main extends App :
        |  def foo(hi: Int, b: Int, c:Int) = ???<<region<<""".stripMargin
    )
  )

  check(
    "def - braced",
    """object Main extends App { def foo(hi: Int, b@@: Int, c:Int) = ??? } """.stripMargin,
    List(
      """object Main extends App { def foo(hi: Int, >>region>>b: Int<<region<<, c:Int) = ??? }""".stripMargin,
      """object Main extends App { def foo(>>region>>hi: Int, b: Int, c:Int<<region<<) = ??? }""".stripMargin,
      """object Main extends App { >>region>>def foo(hi: Int, b: Int, c:Int) = ???<<region<< }""".stripMargin
    )
  )

  check(
    "def - type params",
    """
    object Main extends App { def foo[Type@@ <: T1, B](hi: Int, b: Int, c:Int) = ??? }
    """.stripMargin,
    List(
      """object Main extends App { def foo[>>region>>Type <: T1<<region<<, B](hi: Int, b: Int, c:Int) = ??? }""".stripMargin,
      """object Main extends App { def foo[>>region>>Type <: T1, B<<region<<](hi: Int, b: Int, c:Int) = ??? }""".stripMargin,
      """object Main extends App { >>region>>def foo[Type <: T1, B](hi: Int, b: Int, c:Int) = ???<<region<< }""".stripMargin
    )
  )
}
