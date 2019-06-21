package tests.pc

import tests.BaseCompletionSuite

object CompletionKeywordSuite extends BaseCompletionSuite {
  check(
    "super-template",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  supe@@
      |}
      |""".stripMargin,
    """|superVisorStrategy: Int (commit: '')
       |super (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true
  )

  check(
    "super-def",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  def someMethod = {
      |    supe@@
      |  }
      |}
      |""".stripMargin,
    """|superVisorStrategy: Int
       |super
       |""".stripMargin
  )

  check(
    "super-val",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  val someVal = {
      |    supe@@
      |  }
      |}
      |""".stripMargin,
    """|superVisorStrategy: Int
       |super
       |""".stripMargin
  )

  check(
    "super-var",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  var someVal = {
      |    supe@@
      |  }
      |}
      |""".stripMargin,
    """|superVisorStrategy: Int
       |super
       |""".stripMargin
  )

  check(
    "super-arg",
    """
      |package foo
      |
      |trait A {
      |  final def someMethod(x: Int) = x
      |}
      |
      |object B extends A {
      |  val x = someMethod(supe@@)
      |}
      |""".stripMargin,
    """|super
       |""".stripMargin
  )

  check(
    "val-template",
    """
      |package foo
      |
      |object A {
      |  val value = 42
      |  va@@
      |}
      |""".stripMargin,
    """|value: Int
       |val
       |var
       |override def equals(x$1: Any): Boolean
       |override def hashCode(): Int
       |override def finalize(): Unit
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|value: Int
           |val
           |var
           |override def equals(x$1: Object): Boolean
           |override def hashCode(): Int
           |override def finalize(): Unit
           |""".stripMargin
    )
  )

  check(
    "val-def",
    """
      |package foo
      |
      |object A {
      |  def someMethod = {
      |    va@@
      |  }
      |}
      |""".stripMargin,
    """|val
       |var
       |""".stripMargin
  )

  check(
    "val-arg",
    """
      |package foo
      |
      |object A {
      |  val value = 42
      |  def someMethod(x: Int) = x
      |  someMethod(va@@)
      |}
      |""".stripMargin,
    """|value: Int
       |""".stripMargin
  )

  checkEditLine(
    "val-trailing-space",
    """
      |package foo
      |
      |object A {
      |___
      |}
      |""".stripMargin,
    "  va@@",
    "  val ",
    filter = _ == "val"
  )

  check(
    "return-method",
    """
      |package foo
      |
      |object A {
      |  def someMethod(x: Int) = ret@@
      |}
      |""".stripMargin,
    """|return
       |""".stripMargin
  )

  check(
    "return-val",
    """
      |package foo
      |
      |object A {
      |  val someVal = ret@@
      |}
      |""".stripMargin,
    ""
  )

  check(
    "return-template",
    """
      |package foo
      |
      |object A {
      |  ret@@
      |}
      |""".stripMargin,
    """
      |override def toString(): String
    """.stripMargin
  )

  check(
    "return-toplevel",
    """
      |package foo
      |
      |ret@@
      |""".stripMargin,
    ""
  )

  check(
    "import-top",
    """
      |package foo
      |
      |impo@@
      |
      |class Foo {
      |}
      |""".stripMargin,
    """|import (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true
  )

  check(
    "import-empty",
    """
      |impo@@
      |
      |class Foo {
      |}
      |""".stripMargin,
    "", // you should not use the empty package
    enablePackageWrap = false
  )

  check(
    "abstract-class",
    """
      |package foo
      |
      |abstract cla@@
      |""".stripMargin,
    """|class
       |""".stripMargin
  )

  check(
    "type-toplevel",
    """
      |package foo
      |
      |typ@@
    """.stripMargin,
    ""
  )

  check(
    "type-template",
    """
      |package foo
      |
      |class Foo {
      |  typ@@
      |}
    """.stripMargin,
    """type
    """.stripMargin
  )

  check(
    "type-block",
    """
      |package foo
      |
      |class Foo {
      |  val x = {
      |    val y = {}
      |    typ@@
      |  }
      |}
    """.stripMargin,
    // NOTE(olafur) `type` is technically valid in blocks but they're not completed
    // to reduce noise (we do the same for class, object, trait).
    ""
  )

  check(
    "new-type",
    """
      |package foo
      |
      |trait Foo {
      |  val x: Map[Int, new@@]
      |}
    """.stripMargin,
    ""
  )

  check(
    "new-pattern",
    """
      |package foo
      |
      |trait Foo {
      |  List(1) match {
      |    case new@@
      |  }
      |}
    """.stripMargin,
    ""
  )

  check(
    "super-typeapply",
    """
      |package foo
      |
      |class Foo {
      |  def supervisorStrategy: Int
      |  def callObject = supe@@[Int]
      |}
    """.stripMargin,
    """|supervisorStrategy: Int
       |super
       |""".stripMargin
  )

  check(
    "protected-def",
    """
      |package foo
      |
      |class Foo {
      |  protected def@@
      |}
    """.stripMargin,
    "def"
  )

  check(
    "protected-val",
    """
      |package foo
      |
      |class Foo {
      |  protected va@@
      |}
    """.stripMargin,
    """val
      |var
      |""".stripMargin
  )

}
