package tests.codeactions

class AddMissingMatchCaseLspSuite
    extends BaseCodeActionLspSuite("addMissingMatchCase") {

  check(
    name = "basic",
    input = """|package a
               |
               |sealed trait Tree
               |case class Node(l:Tree, r:Tree) extends Tree
               |case class Leaf(v: String) extends Tree
               |
               |object A {
               |  def method(tree: Tree) = {
               |    <<tree>> match {
               |      case Node(_, _) => ???
               |    }
               |  }
               |}
               |""".stripMargin,
    expectedActions = s"""|Extract `tree` as value
                          |Add missing cases
                          |""".stripMargin,
    selectedActionIndex = 1,
    expectedCode = """|package a
                      |
                      |sealed trait Tree
                      |case class Node(l:Tree, r:Tree) extends Tree
                      |case class Leaf(v: String) extends Tree
                      |
                      |object A {
                      |  def method(tree: Tree) = {
                      |    tree match {
                      |      case Node(_, _) => ???
                      |      case Leaf(_) => ???
                      |    }
                      |  }
                      |}
                      |""".stripMargin,
  )
}
