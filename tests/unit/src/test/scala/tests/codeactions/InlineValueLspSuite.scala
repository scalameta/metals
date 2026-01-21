package tests.codeactions

import scala.meta.internal.metals.codeactions.InlineValueCodeAction

class InlineValueLspSuite extends BaseCodeActionLspSuite("inlineValueRewrite") {
  check(
    "one-use-of-the-value",
    """|object Main {
       | def u : Unit = {
       |  val l : List[Char] = List(1)
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = <<l>>.map(x => m(x))
       | }
       |}
       |""".stripMargin,
    s"""|${InlineValueCodeAction.title("l")}""".stripMargin,
    """|object Main {
       | def u : Unit = {
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = List(1).map(x => m(x))
       | }
       |}
       |""".stripMargin,
    fileName = "Main.scala",
  )

  checkNoAction(
    "extraction-in-def",
    """|object Main {
       | def l : Unit = {
       |  val Some(l) = Some(1)
       |  def get(): Int = <<l>>
       | }
       |}
       |""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "multiple-uses-with-backticks",
    """|object Main {
       |  val `l` : List[Int] = List(1) ++ List(2)
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = `<<l>>`.map(x => m(x))
       |  def get2(): Unit = `l`.map(x => m(x))
       |}
       |""".stripMargin,
    s"""|${InlineValueCodeAction.title("l")}""".stripMargin,
    """|object Main {
       |  val `l` : List[Int] = List(1) ++ List(2)
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = (List(1) ++ List(2)).map(x => m(x))
       |  def get2(): Unit = `l`.map(x => m(x))
       |}
       |""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "multiple-uses-of-the-values",
    """|object Main {
       |  val l : List[Char] = List(1)
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = <<l>>.map(x => m(x))
       |  def get2(): Unit = l.map(x => m(x))
       |}
       |""".stripMargin,
    s"""|${InlineValueCodeAction.title("l")}""".stripMargin,
    """|object Main {
       |  val l : List[Char] = List(1)
       |  def m(i : Int) : Int = ???
       |  def get(): Unit = List(1).map(x => m(x))
       |  def get2(): Unit = l.map(x => m(x))
       |}
       |""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "should-not-delete-if-value-not-local",
    """|object Main {
       |  val v : Int = 1
       |  def someF(x : Int): Int = x + <<v>> + 3
       |}
       |""".stripMargin,
    s"""|${InlineValueCodeAction.title("v")}""".stripMargin,
    """|object Main {
       |  val v : Int = 1
       |  def someF(x : Int): Int = x + 1 + 3
       |}
       |""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "check-pos-on-def",
    """|object Main {
       |  val m: Int = {
       |    val <<a>>: Option[Int] = Some(1)
       |    a match {
       |       case _ => ???
       |    }
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("a")}""".stripMargin,
    """|object Main {
       |  val m: Int = {
       |    Some(1) match {
       |       case _ => ???
       |    }
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "check-adds-brackets",
    """|object Main {
       |  val p : Int = 2
       |  val r : Int = p - 1
       |  val s : Int = s - <<r>>
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("r")}""".stripMargin,
    """|object Main {
       |  val p : Int = 2
       |  val r : Int = p - 1
       |  val s : Int = s - (p - 1)
       |}""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "check-interpolates-string-properly",
    """|object Main {
       |  def f(): Unit = {
       |    val x = "hi"
       |    println(s"$<<x>>!")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(): Unit = {
       |    println(s"${"hi"}!")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolates-expression-properly",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val x = 1 + y
       |    println(s"$<<x>>$y")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"${1 + y}$y")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolation-no-unneeded-curly-braces",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val x = y
       |    println(s"$<<x>>$y")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"$y$y")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolation-no-extra-curly-braces",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val x = y + 1
       |    println(s"${<<x>>}$y")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"${y + 1}$y")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolation-inlining-inside-curly-exp-does-not-add-curly",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val x = y - 1
       |    println(s"${<<x>> - y}")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"${y - 1 - y}")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolation-inlining-within-curly-exp-adds-brackets",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val x = y - 1
       |    println(s"${y - <<x>>}")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"${y - (y - 1)}")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("x"),
  )

  check(
    "check-interpolation-dollar-sign-variable",
    """|object Main {
       |  def f(y: Int): Unit = {
       |    val `$x` = y + 1
       |    println(s"${<<`$x`>>}$y")
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("$x")}""".stripMargin,
    """|object Main {
       |  def f(y: Int): Unit = {
       |    println(s"${y + 1}$y")
       |  }
       |}""".stripMargin,
    fileName = "Main.scala",
    filterAction = _.getTitle == InlineValueCodeAction.title("$x"),
  )

  checkNoAction(
    "check-no-inline-when-not-local",
    """|object Main {
       |  val p : Int = 2
       |  val <<r>> : Int = p - 1
       |  val s : Int = s - r
       |}""".stripMargin,
    fileName = "Main.scala",
  )

  check(
    "check-local-object",
    """|object Main {
       |  def hello: Unit = {
       |     object O {
       |        val <<a>>: Int = 123
       |        val b: Int = a
       |     }
       |  }
       |}""".stripMargin,
    s"""|${InlineValueCodeAction.title("a")}""".stripMargin,
    """|object Main {
       |  def hello: Unit = {
       |     object O {
       |        val b: Int = 123
       |     }
       |  }
       |}""".stripMargin,
  )
}
