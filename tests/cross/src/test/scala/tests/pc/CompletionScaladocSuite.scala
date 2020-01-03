package tests.pc

import tests.BaseCompletionSuite

object CompletionScaladocSuite extends BaseCompletionSuite {
  check(
    "scaladoc-methoddef",
    """
      |object A {
      |  /**@@
      |  def test(x: Int, y: Int): Int = ???
      |}""".stripMargin,
    """|/** */Scaladoc Comment
       |""".stripMargin
  )

  check(
    "scaladoc-classdef",
    """
      |object A {
      |  /**@@
      |  case class(x: Int) {}
      |}""".stripMargin,
    """|/** */Scaladoc Comment
       |""".stripMargin
  )

  check(
    "scaladoc-no-completion",
    """
      |object A {
      |  /**@@
      |}""".stripMargin,
    ""
  )

  checkEdit(
    "scaladoc-methoddef-edit",
    """|object A {
       |  /**@@
       |  def test1(param1: Int, param2: Int): Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |  *
       |  * @param param1 $0
       |  * @param param2
       |  * @return
       |  */
       |  def test1(param1: Int, param2: Int): Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-classdef-edit",
    """|object A {
       |  /**@@
       |  class Test1(param1: Int, param2: Int) {}
       |  class Test2(param1: Int, param2: Int, param3: Int) {}
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |  *
       |  * @param param1 $0
       |  * @param param2
       |  */
       |  class Test1(param1: Int, param2: Int) {}
       |  class Test2(param1: Int, param2: Int, param3: Int) {}
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-valdef-edit",
    """|object A {
       |  /**@@
       |  val x = 1
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |  *
       |  */
       |  val x = 1
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-valdef-edit",
    """|object A {
       |  /**@@
       |  val x = 1
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |  *
       |  */
       |  val x = 1
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-objectdef-edit",
    """|/**@@
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin,
    """|/**
       |  *
       |  */
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-objectdef-edit",
    """|/**@@
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin,
    """|/**
       |  *
       |  */
       |object A {
       |  // do not calculate scaladoc based on the method
       |  def test(x: Int): Int = ???
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-defdef-nested-edit",
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
       |  *
       |  * @param y $0
       |  * @return
       |  */
       |    def nest(y: Int) = ???
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )

  checkEdit(
    "scaladoc-defdef-no-param-cursor",
    """|object A {
       |  /**@@
       |  def test1: Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    """|object A {
       |  /**
       |  *
       |  * @return $0
       |  */
       |  def test1: Int = ???
       |  def test2(param1: Int, param2: Int, param3: Int): Int = ???
       |}
       |""".stripMargin,
    filter = _.contains("/** */")
  )
}
