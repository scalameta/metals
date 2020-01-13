package tests.pc

import tests.BaseCompletionSuite

object CompletionScaladocSuite extends BaseCompletionSuite {
  check(
    "methoddef-label",
    """
      |object A {
      |  /**@@
      |  def test(x: Int, y: Int): Int = ???
      |}""".stripMargin,
    """|/** */Scaladoc Comment
       |""".stripMargin
  )

  check(
    "classdef-label",
    """
      |object A {
      |  /**@@
      |  case class(x: Int) {}
      |}""".stripMargin,
    """|/** */Scaladoc Comment
       |""".stripMargin
  )

  check(
    "no-completion",
    """
      |object A {
      |  /**@@
      |}""".stripMargin,
    ""
  )

  checkEdit(
    "methoddef",
    """|object A {
       |  /**@@
       |  def test1(param1: Int, param2: Int): Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @param param1
       |    * @param param2
       |    * @return
       |    */
       |  def test1(param1: Int, param2: Int): Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin
  )

  checkEdit(
    "classdef",
    """|object A {
       |  /**@@
       |  class Test1(param1: Int, param2: Int) {}
       |  class Test2(param1: Int, param2: Int, param3: Int) {}
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @param param1
       |    * @param param2
       |    */
       |  class Test1(param1: Int, param2: Int) {}
       |  class Test2(param1: Int, param2: Int, param3: Int) {}
       |}
       |""".stripMargin
  )

  checkEdit(
    "valdef",
    """|object A {
       |  /**@@
       |  val x = 1
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    */
       |  val x = 1
       |}
       |""".stripMargin
  )

  checkEdit(
    "objectdef",
    """|/**@@
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin,
    """|/**
       |  * $0
       |  */
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin
  )

  checkEdit(
    "defdef-nested",
    """|object A {
       |  def test(x: Int): Int = {
       |    /**@@
       |    def nest(y: Int) = ???
       |  }
       |}
       |""".stripMargin,
    """|object A {
       |  def test(x: Int): Int = {
       |    /**
       |      * $0
       |      *
       |      * @param y
       |      * @return
       |      */
       |    def nest(y: Int) = ???
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "classdef-nested",
    """|object A {
       |  /**@@
       |  case class B(x: Int) {
       |    case class C(y: Int) {}
       |  }
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @param x
       |    */
       |  case class B(x: Int) {
       |    case class C(y: Int) {}
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "trait-classdef-nested",
    """|object A {
       |  /**@@
       |  trait B {
       |    // do not complete scaladef for class C
       |    case class C(y: Int) {}
       |  }
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    */
       |  trait B {
       |    // do not complete scaladef for class C
       |    case class C(y: Int) {}
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "defdef-no-param-cursor",
    """|object A {
       |  /**@@
       |  def test1: Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @return
       |    */
       |  def test1: Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin
  )

  checkEdit(
    "defdef-returns-unit",
    """|// Don't add @return line for a method whose return type is Unit.
       |object A {
       |  /**@@
       |  def test(param1: Int, param2: Int): Unit = ???
       |}
       |""".stripMargin,
    """|// Don't add @return line for a method whose return type is Unit.
       |object A {
       |  /**
       |    * $0
       |    *
       |    * @param param1
       |    * @param param2
       |    */
       |  def test(param1: Int, param2: Int): Unit = ???
       |}
       |""".stripMargin
  )

  checkEdit(
    "defdef-returns-inferred-unit",
    """|// Don't add @return line for a method whose return type is Unit.
       |object A {
       |  /**@@
       |  def test(param1: Int, param2: Int) = {}
       |}
       |""".stripMargin,
    """|// Don't add @return line for a method whose return type is Unit.
       |object A {
       |  /**
       |    * $0
       |    *
       |    * @param param1
       |    * @param param2
       |    */
       |  def test(param1: Int, param2: Int) = {}
       |}
       |""".stripMargin
  )

  checkEdit(
    "defdef-evidence",
    """|// do not add compiler generated param like `evidence$1`
       |object A {
       |  /**@@
       |  def test[T: Ordering](x: T, y: T): T = if(x < y) x else y
       |}
       |""".stripMargin,
    """|// do not add compiler generated param like `evidence$1`
       |object A {
       |  /**
       |    * $0
       |    *
       |    * @param x
       |    * @param y
       |    * @return
       |    */
       |  def test[T: Ordering](x: T, y: T): T = if(x < y) x else y
       |}
       |""".stripMargin
  )

  checkEdit(
    "classdef-evidence",
    """|object A {
       |  /**@@
       |  case class Test[T: Ordering](x: T, y: T) {}
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @param x
       |    * @param y
       |    */
       |  case class Test[T: Ordering](x: T, y: T) {}
       |}
       |""".stripMargin
  )

  checkEdit(
    "classdef-curried",
    """|object A {
       |  /**@@
       |  case class Test(a: Int, b: String)(c: Long)
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |    * $0
       |    *
       |    * @param a
       |    * @param b
       |    * @param c
       |    */
       |  case class Test(a: Int, b: String)(c: Long)
       |}
       |""".stripMargin
  )
}
