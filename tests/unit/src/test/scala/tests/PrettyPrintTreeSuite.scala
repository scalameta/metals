package tests

import munit.Location

class PrettyPrintTreeSuite extends BaseSuite {
  def check(name: String, tree: PrettyPrintTree, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(name) {
      assertNoDiff(tree.toString(), expected)
    }
  }
  def t(
      value: String,
      children: PrettyPrintTree*
  ): PrettyPrintTree = PrettyPrintTree(value, children.toList)

  check(
    "shallow",
    t("root"),
    "root"
  )
  check(
    "deep",
    t("root", t("child1"), t("child2")),
    """root
      |  child1
      |  child2
      |""".stripMargin
  )
  check(
    "deeper",
    t(
      "root",
      t("child1", t("grandchild1")),
      t("child2", t("grandchild2"))
    ),
    """|root
       |  child1
       |    grandchild1
       |  child2
       |    grandchild2
       |""".stripMargin
  )
}
