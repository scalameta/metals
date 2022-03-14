package tests.codeactions

import scala.meta.internal.metals.codeactions.CreateCompanionObjectCodeAction
import scala.meta.internal.metals.codeactions.ExtractRenameMember

class CompanionObjectSuite extends BaseCodeActionLspSuite("companionObject") {

  check(
    "insert-companion-object",
    """|class F<<>>oo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("class", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|class Foo {
       |
       |}
       |
       |object Foo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "insert-companion-object-inside-package",
    """|package baz
       |
       |class F<<>>oo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("class", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|package baz
       |
       |class Foo {
       |
       |}
       |
       |object Foo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "insert-companion-object-inside-parent-object",
    """|object Baz {
       |  class F<<>>oo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|object Baz {
       |  class Foo {
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin
  )

  check(
    "insert-companion-object-inside-parent-class",
    """|class Baz {
       |  class F<<>>oo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|class Baz {
       |  class Foo {
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin
  )

  check(
    "don't create existing companion object",
    """|class Fo<<>>o{
       |
       |}
       |
       |object Foo {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("class", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """|class Foo{
       |
       |}
       |
       |object Foo {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "don't create existing companion object inside a parent object",
    """|object Bar{
       |  class Fo<<>>o{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """object Bar{
      |  class Foo{
      |
      |  }
      |
      |  object Foo {
      |
      |  }
      |
      |  object Baz{
      |
      |  }
      |
      |}
      |""".stripMargin
  )

  check(
    "don't create existing companion object inside a parent package",
    """|package bar
       |  class Fo<<>>o{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("class", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """|package bar
       |  class Foo{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "insert companion object of trait",
    """|trait F<<>>oo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("trait", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|trait Foo {
       |
       |}
       |
       |object Foo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "insert companion object of trait inside package",
    """|package baz
       |
       |trait F<<>>oo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("trait", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|package baz
       |
       |trait Foo {
       |
       |}
       |
       |object Foo {
       |
       |}
       |
       |class Bar {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "insert companion object of trait inside parent object",
    """|object Baz {
       |  trait F<<>>oo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|object Baz {
       |  trait Foo {
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin
  )

  check(
    "insert companion object of trait inside parent class",
    """|class Baz {
       |  trait F<<>>oo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|class Baz {
       |  trait Foo {
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  class Bar {
       |
       |  }
       |}
       |""".stripMargin
  )

  check(
    "don't create existing companion object of trait",
    """|trait Fo<<>>o{
       |
       |}
       |
       |object Foo {
       |
       |}
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("trait", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """|trait Foo{
       |
       |}
       |
       |object Foo {
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "don't create existing companion object of trait inside a parent object",
    """|object Bar{
       |  trait Fo<<>>o{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """object Bar{
      |  trait Foo{
      |
      |  }
      |
      |  object Foo {
      |
      |  }
      |
      |  object Baz{
      |
      |  }
      |
      |}
      |""".stripMargin
  )

  check(
    "don't create existing companion object of trait inside a parent package",
    """|package bar
       |  trait Fo<<>>o{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("trait", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectInfo}
        |""".stripMargin,
    """|package bar
       |  trait Foo{
       |
       |  }
       |
       |  object Foo {
       |
       |  }
       |
       |  object Baz{
       |
       |  }
       |
       |
       |""".stripMargin,
    selectedActionIndex = 1
  )

//  check(
//    "insert companion object of braceless enum inside parent object".only,
//    """|object Baz:
//       |  enum F<<>>oo:
//       |    case a
//       |    def fooMethod(): Unit = {
//       |      val a = 3
//       |    }
//       |
//       |  class Bar {}
//       |""",
//    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
//        |""".stripMargin,
//        """|object Baz:
//       |  enum Foo:
//       |    case a
//       |    def fooMethod(): Unit = {
//       |      val a = 3
//       |    }
//       |
//       |  object Foo:
//       |    ???
//       |
//       |  class Bar {}
//       |"""
//  )

}
