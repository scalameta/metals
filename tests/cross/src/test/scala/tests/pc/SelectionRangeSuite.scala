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
      """object Main extends App { def foo(hi: Int, >>region>>b<<region<<: Int, c:Int) = ??? }""".stripMargin,
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
      """object Main extends App { def foo[>>region>>Type<<region<< <: T1, B](hi: Int, b: Int, c:Int) = ??? }""".stripMargin,
      """object Main extends App { def foo[>>region>>Type <: T1<<region<<, B](hi: Int, b: Int, c:Int) = ??? }""".stripMargin,
      """object Main extends App { def foo[>>region>>Type <: T1, B<<region<<](hi: Int, b: Int, c:Int) = ??? }""".stripMargin,
      """object Main extends App { >>region>>def foo[Type <: T1, B](hi: Int, b: Int, c:Int) = ???<<region<< }""".stripMargin
    )
  )

  check(
    "def - lhs",
    """
    object Main extends App { def `foo ba@@r` = ??? }
    """.stripMargin,
    List(
      """object Main extends App { def >>region>>`foo bar`<<region<< = ??? }""".stripMargin,
      """object Main extends App { >>region>>def `foo bar` = ???<<region<< }""".stripMargin
    )
  )

  check(
    "expr - apply",
    """
    object Main extends App { def foo = bar.baz(1, math.floor(p@@i), 2) }
    """.stripMargin,
    List(
      """object Main extends App { def foo = bar.baz(1, math.floor(>>region>>pi<<region<<), 2) }""",
      """object Main extends App { def foo = bar.baz(1, >>region>>math.floor(pi)<<region<<, 2) }""",
      """object Main extends App { def foo = bar.baz(>>region>>1, math.floor(pi), 2<<region<<) }""",
      """object Main extends App { def foo = >>region>>bar.baz(1, math.floor(pi), 2)<<region<< }""",
      """object Main extends App { >>region>>def foo = bar.baz(1, math.floor(pi), 2)<<region<< }"""
    )
  )

  check(
    "expr - backticked",
    """
    object Main extends App { def foo = `foo ba@@r` + 1 }
    """.stripMargin,
    List(
      """object Main extends App { def foo = >>region>>`foo bar`<<region<< + 1 }""",
      """object Main extends App { def foo = >>region>>`foo bar` +<<region<< 1 }""",
      """object Main extends App { def foo = >>region>>`foo bar` + 1<<region<< }""",
      """object Main extends App { >>region>>def foo = `foo bar` + 1<<region<< }"""
    )
  )

  check(
    "type - apply",
    """
    object Main extends App { def foo: Tuple3[Int, List[In@@t], Double] = ??? }
    """.stripMargin,
    List(
      """object Main extends App { def foo: Tuple3[Int, List[>>region>>Int<<region<<], Double] = ??? }""",
      """object Main extends App { def foo: Tuple3[Int, >>region>>List[Int]<<region<<, Double] = ??? }""",
      """object Main extends App { def foo: Tuple3[>>region>>Int, List[Int], Double<<region<<] = ??? }""",
      """object Main extends App { def foo: >>region>>Tuple3[Int, List[Int], Double]<<region<< = ??? }""",
      """object Main extends App { >>region>>def foo: Tuple3[Int, List[Int], Double] = ???<<region<< }"""
    )
  )

  check(
    "constructor-argument",
    """
    class Foo(val ar@@g: Int)
    """.stripMargin,
    List(
      """class Foo(val >>region>>arg<<region<<: Int)""",
      """class Foo(>>region>>val arg: Int<<region<<)"""
    )
  )

  check(
    "object - backticked",
    """
    object `Foo B@@ar Baz` extends SomeTrait
    """.stripMargin,
    List(
      """object >>region>>`Foo Bar Baz`<<region<< extends SomeTrait"""
    )
  )

  check(
    "select - backticked",
    """
    object Main extends App { def foo = Other.`foo ba@@r` }
    """.stripMargin,
    List(
      """object Main extends App { def foo = Other.>>region>>`foo bar`<<region<< }""",
      """object Main extends App { def foo = >>region>>Other.`foo bar`<<region<< }""",
      """object Main extends App { >>region>>def foo = Other.`foo bar`<<region<< }"""
    )
  )
}
