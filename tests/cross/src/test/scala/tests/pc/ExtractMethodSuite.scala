package tests.pc

import tests.BaseExtractMethodSuite

class ExtractMethodSuite extends BaseExtractMethodSuite {
  checkEdit(
    "simple-expr",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  @@val a = <<123 + method(b)>>
        |}""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  def newMethod(): Int =
        |    123 + method(b)
        |
        |  val a = newMethod()
        |}""".stripMargin
  )

  checkEdit(
    "no-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(b, 10)>>
        |  }
        |
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(): Int = {
        |    val b = 2
        |    123 + method(b, 10)
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod()
        |  }
        |
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(): Int =
                         |    val b = 2
                         |    123 + method(b, 10)
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod()
                         |  }
                         |
                         |}""".stripMargin)
  )

  checkEdit(
    "val-trait",
    """|
       |trait Simple{
       |  def well(str: String) = ""
       |}
       |
       |object A{
       |  @@
       |  <<val s: Simple = ???
       |  s.well("")>>
       |}""".stripMargin,
    """|trait Simple{
       |  def well(str: String) = ""
       |}
       |
       |object A{
       |
       |  def newMethod(): String = {
       |    val s: Simple = ???
       |    s.well("")
       |  }
       |  newMethod()
       |}
       |""".stripMargin,
    Map(
      "3" ->
        """|trait Simple{
           |  def well(str: String) = ""
           |}
           |
           |object A{
           |
           |  def newMethod(): String =
           |    val s: Simple = ???
           |    s.well("")
           |
           |  newMethod()
           |}
           |""".stripMargin
    )
  )

  checkEdit(
    "single-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(c, 10)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(c: Int): Int = {
        |    val b = 2
        |    123 + method(c, 10)
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(c: Int): Int =
                         |    val b = 2
                         |    123 + method(c, 10)
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod(c)
                         |  }
                         |}""".stripMargin)
  )

  checkEdit(
    "name-gen",
    s"""|object A{
        |  def newMethod() = 1
        |  def newMethod0(a: Int) = a + 1
        |  def method(i: Int) = i + i
        |  @@val a = <<method(5)>>
        |}""".stripMargin,
    s"""|object A{
        |  def newMethod() = 1
        |  def newMethod0(a: Int) = a + 1
        |  def method(i: Int) = i + i
        |  def newMethod1(): Int =
        |    method(5)
        |
        |  val a = newMethod1()
        |}""".stripMargin
  )

  checkEdit(
    "multi-param",
    s"""|object A{
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  @@val a = {
        |    val c = 5
        |    val b = 4
        |    <<123 + method(c, b) + method(b,c)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  def newMethod(b: Int, c: Int): Int =
        |    123 + method(c, b) + method(b,c)
        |
        |  val a = {
        |    val c = 5
        |    val b = 4
        |    newMethod(b, c)
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "higher-scope",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    @@def f() = {
        |      val c = 1
        |      <<val d = 3
        |      method(d, b, c)>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    def newMethod(c: Int): Int = {
        |      val d = 3
        |      method(d, b, c)
        |    }
        |    def f() = {
        |      val c = 1
        |      newMethod(c)
        |    }
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A{
                         |  val b = 4
                         |  def method(i: Int, j: Int, k: Int) = i + j + k
                         |  val a = {
                         |    def newMethod(c: Int): Int =
                         |      val d = 3
                         |      method(d, b, c)
                         |
                         |    def f() = {
                         |      val c = 1
                         |      newMethod(c)
                         |    }
                         |  }
                         |}""".stripMargin)
  )

  checkEdit(
    "match",
    s"""|object A {
        |  @@val a = {
        |    val b = 4
        |    <<b + 2 match {
        |      case _ => b
        |    }>>
        |  }
        |}""".stripMargin,
    s"""|object A {
        |  def newMethod(b: Int): Int =
        |    b + 2 match {
        |      case _ => b
        |    }
        |
        |  val a = {
        |    val b = 4
        |    newMethod(b)
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "nested-declarations",
    s"""|object A {
        |  @@val a = {
        |    val c = 1
        |    <<val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2>>
        |  }
        |}""".stripMargin,
    s"""|object A {
        |  def newMethod(c: Int): Int = {
        |    val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A {
                         |  def newMethod(c: Int): Int =
                         |    val b = {
                         |      val c = 2
                         |      c + 1
                         |    }
                         |    c + 2
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod(c)
                         |  }
                         |}""".stripMargin)
  )

  checkEdit(
    "class-param",
    s"""|object A{
        |  @@class B(val b: Int) {
        |    def f2 = <<b + 2>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def newMethod(b: Int): Int =
        |    b + 2
        |
        |  class B(val b: Int) {
        |    def f2 = newMethod(b)
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "method-param",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1(a: Int) = {
        |    <<method(a)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(a: Int): Int =
        |    method(a)
        |
        |  def f1(a: Int) = {
        |    newMethod(a)
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "method-type",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1[T](a: T) = {
        |    <<a>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod[T](a: T): T =
        |    a
        |
        |  def f1[T](a: T) = {
        |    newMethod(a)
        |  }
        |}""".stripMargin
  )
  checkEdit(
    "method-type-no-param",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1[T](a: T) = {
        |    <<Set.empty[T]>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod[T](): Set[T] =
        |    Set.empty[T]
        |
        |  def f1[T](a: T) = {
        |    newMethod()
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "inner-conflict",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val d = 3
        |    <<val b = {
        |      val d = 4
        |      d + 1
        |    }
        |    123 + method(b, 10)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(): Int = {
        |    val b = {
        |      val d = 4
        |      d + 1
        |    }
        |    123 + method(b, 10)
        |  }
        |  val a = {
        |    val d = 3
        |    newMethod()
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(): Int =
                         |    val b = {
                         |      val d = 4
                         |      d + 1
                         |    }
                         |    123 + method(b, 10)
                         |
                         |  val a = {
                         |    val d = 3
                         |    newMethod()
                         |  }
                         |}""".stripMargin)
  )

  checkEdit(
    "extract-def",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1(a: Int) = {
        |    def m2(b: Int) = b + 1
        |    <<method(2 + m2(a))>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(a: Int, m2: Int => Int): Int =
        |    method(2 + m2(a))
        |
        |  def f1(a: Int) = {
        |    def m2(b: Int) = b + 1
        |    newMethod(a, m2)
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "extract-def-mult-params-lists",
    s"""|object Hello {
        |  @@def m(): Unit = {
        |    def m2[T](a: T, j: Int)(i : Int) = List(a)
        |    val a = "aa"
        |    <<m2(a, 2)(2)>>
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  def newMethod[T](a: String, m2: (T, Int) => Int => List[T]): List[String] =
        |    m2(a, 2)(2)
        |
        |  def m(): Unit = {
        |    def m2[T](a: T, j: Int)(i : Int) = List(a)
        |    val a = "aa"
        |    newMethod(a, m2)
        |  }
        |}
        |""".stripMargin
  )

  checkEdit(
    "extract-def-mult-type-params",
    s"""|object Hello {
        |  @@def m[T](a: T): Unit = {
        |    def m2[F](a: F, j: Int)(i : Int) = List(a)
        |    <<m2(a, 2)(2)>>
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  def newMethod[F, T](a: T, m2: (F, Int) => Int => List[F]): List[T] =
        |    m2(a, 2)(2)
        |
        |  def m[T](a: T): Unit = {
        |    def m2[F](a: F, j: Int)(i : Int) = List(a)
        |    newMethod(a, m2)
        |  }
        |}
        |""".stripMargin
  )

  checkEdit(
    "extract-def-partial",
    s"""|object Hello {
        |  @@def m(): Unit = {
        |    def m2[T](a: T, j: Int)(i : Int) = List(a)
        |    <<m2("aa", 2)>>(2)
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  def newMethod[T](m2: (T, Int) => Int => List[T]): Int => List[String] =
        |    m2("aa", 2)
        |
        |  def m(): Unit = {
        |    def m2[T](a: T, j: Int)(i : Int) = List(a)
        |    newMethod(m2)(2)
        |  }
        |}
        |""".stripMargin
  )

  checkEdit(
    "extract-def-no-args",
    s"""|object Hello {
        |  @@def m(): Unit = {
        |    def m2 = 9
        |    <<m2 + 3>>
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  def newMethod(m2: => Int): Int =
        |    m2 + 3
        |
        |  def m(): Unit = {
        |    def m2 = 9
        |    newMethod(m2)
        |  }
        |}
        |""".stripMargin
  )

  checkEdit(
    "extract-def-no-args2",
    s"""|object Hello {
        |  @@def m(): Unit = {
        |    def m2() = 9
        |    <<m2() + 3>>
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  def newMethod(m2: () => Int): Int =
        |    m2() + 3
        |
        |  def m(): Unit = {
        |    def m2() = 9
        |    newMethod(m2)
        |  }
        |}
        |""".stripMargin
  )

  checkEdit(
    "extract-class",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@class Car(val color: Int) {
        |    def add(other: Car): Car = {
        |      <<new Car(other.color + color)>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(color: Int, other: Car): Car =
        |    new Car(other.color + color)
        |
        |  class Car(val color: Int) {
        |    def add(other: Car): Car = {
        |      newMethod(color, other)
        |    }
        |  }
        |}""".stripMargin
  )

  checkEdit(
    "empty-lines",
    s"""|object Hello {
        |  val a: Int = 2
        |  @@def m() = {
        |    <<print("1")
        |
        |    print(a)>>
        |
        |    a + 2
        |  }
        |}
        |""".stripMargin,
    s"""|object Hello {
        |  val a: Int = 2
        |  def newMethod(): Unit = {
        |    print("1")
        |
        |    print(a)
        |  }
        |  def m() = {
        |    newMethod()
        |
        |    a + 2
        |  }
        |}
        |""".stripMargin,
    compat = Map(
      "3" ->
        s"""|object Hello {
            |  val a: Int = 2
            |  def newMethod(): Unit =
            |    print("1")
            |
            |    print(a)
            |
            |  def m() = {
            |    newMethod()
            |
            |    a + 2
            |  }
            |}
            |""".stripMargin
    )
  )

  checkEdit(
    "i6476",
    """|object O {
       |  class C
       |  def foo(i: Int)(implicit o: C) = i
       |
       |  @@val o = {
       |    implicit val c = new C
       |    <<foo(2)>>
       |    ???
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  class C
       |  def foo(i: Int)(implicit o: C) = i
       |
       |  def newMethod()(implicit o: C): Int =
       |    foo(2)
       |
       |  val o = {
       |    implicit val c = new C
       |    newMethod()
       |    ???
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|object O {
                |  class C
                |  def foo(i: Int)(implicit o: C) = i
                |
                |  def newMethod()(given c: C): Int =
                |    foo(2)
                |
                |  val o = {
                |    implicit val c = new C
                |    newMethod()
                |    ???
                |  }
                |}
                |""".stripMargin
    )
  )

  checkEdit(
    "i6476-2",
    """|object O {
       |  class C
       |  def foo(i: Int)(implicit o: C) = i
       |
       |  @@val o = {
       |    <<foo(2)(new C)>>
       |    ???
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  class C
       |  def foo(i: Int)(implicit o: C) = i
       |
       |  def newMethod(): Int =
       |    foo(2)(new C)
       |
       |  val o = {
       |    newMethod()
       |    ???
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "i6476-3".tag(IgnoreScala2),
    """|object O {
       |  class C
       |  class D
       |  def foo(i: Int)(using o: C)(x: Int)(using d: D) = i
       |
       |  @@val o = {
       |    given C = new C
       |    given D = new D
       |    val w = 2
       |    <<foo(w)(w)>>
       |    ???
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  class C
       |  class D
       |  def foo(i: Int)(using o: C)(x: Int)(using d: D) = i
       |
       |  def newMethod(w: Int)(given given_C: C, given_D: D): Int =
       |    foo(w)(w)
       |
       |  val o = {
       |    given C = new C
       |    given D = new D
       |    val w = 2
       |    newMethod(w)
       |    ???
       |  }
       |}
       |""".stripMargin
  )

}
