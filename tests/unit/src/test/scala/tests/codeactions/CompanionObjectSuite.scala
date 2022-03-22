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
    "bracefull-companion-object-insert-for-scala2-file-end",
    """|case class F<<>>oo()""".stripMargin,
    s"""|${ExtractRenameMember.renameFileAsClassTitle("A.scala", "Foo")}
        |${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|case class Foo()
       |
       |object Foo {
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

  checkNoAction(
    "existing-companion-object-with-parent",
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
       |""".stripMargin
  )

  check(
    "insert-companion-object-of-trait",
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

}
