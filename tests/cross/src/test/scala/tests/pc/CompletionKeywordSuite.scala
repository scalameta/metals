package tests.pc

import tests.BaseCompletionSuite

class CompletionKeywordSuite extends BaseCompletionSuite {

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
    includeCommitCharacter = true,
  )

  check(
    "comment",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  // tr@@
      |}
      |""".stripMargin,
    "",
    includeCommitCharacter = true,
  )

  check(
    "scala-doc",
    """
      |package foo
      |
      |trait A {
      |  final def superVisorStrategy = 1
      |}
      |
      |object B extends A {
      |  /** tr@@
      |  *
      |  **/
      |}
      |""".stripMargin,
    "",
    includeCommitCharacter = true,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
           |""".stripMargin,
      "3" ->
        """|value: Int
           |val
           |var
           |""".stripMargin,
    ),
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
       |""".stripMargin,
  )

  check(
    "given-def",
    """
      |package foo
      |
      |object A {
      |  def someMethod = {
      |    gi@@
      |  }
      |}
      |""".stripMargin,
    """|given (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true,
    compat = Map(
      "2" -> ""
    ),
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
       |""".stripMargin,
    topLines = Some(1),
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
    filter = _ == "val",
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
       |""".stripMargin,
    // methods add in 3.2.1
    filter = item => !item.contains("retains"),
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
    "",
    // methods add in 3.2.1
    filter = item => !item.contains("retains"),
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
    """.stripMargin,
    compat = Map(
      "3" -> ""
    ),
    // methods add in 3.2.1
    filter = item => !item.contains("retains"),
  )

  check(
    "return-toplevel",
    """
      |package foo
      |
      |ret@@
      |""".stripMargin,
    "",
    // methods add in 3.2.1
    filter = item => !item.contains("retains"),
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
    includeCommitCharacter = true,
  )

  check(
    "import-empty",
    """
      |impo@@
      |
      |class Foo {
      |}
      |""".stripMargin,
    """|import (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true,
    enablePackageWrap = false,
  )

  check(
    "abstract-class",
    """
      |package foo
      |
      |abstract cla@@
      |""".stripMargin,
    """|class
       |""".stripMargin,
  )

  check(
    "type-toplevel",
    """
      |package foo
      |
      |typ@@
    """.stripMargin,
    "",
    compat = Map(
      "3" -> "type"
    ),
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
    """.stripMargin,
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
    "",
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
    "",
    // to avoid newMain annotation
    filter = str => !str.contains("newMain"),
  )
  // TODO: Should provide empty completions
  // The issue is that the tree looks the same as for `case @@` (it doesn't see `new`)
  // Issue: https://github.com/scalameta/metals/issues/4367
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
    "",
    // to avoid newMain annotation
    filter = str => !str.contains("newMain"),
    compat = Map(
      "3" ->
        """|head :: next scala.collection.immutable
           |Nil scala.collection.immutable""".stripMargin
    ),
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
       |""".stripMargin,
  )

  check(
    "protected-def",
    """
      |package foo
      |
      |class Foo {
      |  protected de@@
      |}
    """.stripMargin,
    "def",
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
      |""".stripMargin,
  )

  check(
    "topLevel",
    "@@",
    """|abstract class
       |case class
       |class
       |final
       |import
       |object
       |package
       |private
       |protected
       |sealed abstract class
       |sealed class
       |sealed trait
       |trait
       |""".stripMargin,
    compat = Map(
      "3" -> """|def
                |val
                |lazy val
                |inline
                |var
                |given
                |extension
                |type
                |class
                |enum
                |case class
                |trait
                |object
                |package
                |import
                |final
                |private
                |protected
                |abstract class
                |sealed trait
                |sealed abstract class
                |sealed class
                |implicit
                |""".stripMargin
    ),
  )

  check(
    "using",
    """|object A{
       |  def hello(u@@)
       |}""".stripMargin,
    """|using (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true,
    compat = Map(
      "2" -> ""
    ),
  )

  check(
    "not-using",
    """|object A{
       |  def hello(a: String, u@@)
       |}""".stripMargin,
    "",
  )

  check(
    "extends-class",
    """
      |package foo
      |
      |class Foo ext@@
    """.stripMargin,
    """|extends
       |""".stripMargin,
  )

  check(
    "extends-obj",
    """
      |package foo
      |
      |object Foo ext@@
    """.stripMargin,
    """|extends
       |""".stripMargin,
  )

  check(
    "extends-trait",
    """
      |package foo
      |
      |trait Foo ext@@ {}
    """.stripMargin,
    """|extends
       |""".stripMargin,
  )

  check(
    "extends-with-constructor",
    """
      |package foo
      |
      |class Foo(x: Int) ext@@
    """.stripMargin,
    """|extends
       |""".stripMargin,
  )

  check(
    "no-extends",
    """
      |package foo
      |
      |object Main {
      |  def main = {
      |    foo.ext@@
      |  }
      |}
    """.stripMargin,
    "",
  )

  check(
    "no-extends-paren",
    """
      |package foo
      |
      |object Main {
      |  def main = {
      |    foo(i) ex@@
      |  }
      |}
    """.stripMargin,
    "",
  )

  check(
    "extends-limitation",
    """
      |package foo
      |
      |// can't provide extends keyword completion if there's newline between class
      |// because the completion engine tokenize only the line 
      |class Main
      |  exten@@
    """.stripMargin,
    "",
    compat =
      Map( // it works in Scala3 because `completionPos.cursorPos` gives us a `class Main\n exten`
        "3" ->
          """|extends
             |""".stripMargin
      ),
  )

}
