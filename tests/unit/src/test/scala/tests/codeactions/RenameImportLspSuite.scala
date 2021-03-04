package tests.codeactions

import scala.meta.internal.metals.codeactions.RenameImport

class RenameImportLspSuite
    extends BaseCodeActionLspSuite("implementAbstractMembers") {

  check(
    "simple",
    """|package a
       |
       |import java.util.<<List>>
       |object A {
       |  val alpha = 123
       |}
       |""".stripMargin,
    s"""|${RenameImport.title}
        |""".stripMargin,
    """|package a
       |
       |import java.util.{ List => NewRenamedSymbol }
       |object A {
       |  val alpha = 123
       |}
       |""".stripMargin,
    expectNoDiagnostics = false
  )

  check(
    "with-existing".only,
    """|package a
       |
       |import java.util.<<List>>
       |import java.util.LinkedList
       |
       |object A {
       |  val alpha : List[Int] = new LinkedList[Int]()
       |}
       |""".stripMargin,
    s"""|${RenameImport.title}
        |""".stripMargin,
    """|package a
       |
       |import java.util.{ List => NewRenamedSymbol }
       |import java.util.LinkedList
       |
       |object A {
       |  val alpha : NewRenamedSymbol[Int] = new LinkedList[Int]()
       |}
       |""".stripMargin,
    expectNoDiagnostics = false
  )
}
