package tests.pc

import tests.BaseAutoImportsSuite

class AutoImportExtensionMethodsSuite extends BaseAutoImportsSuite {

  override def ignoreScalaVersion: Some[IgnoreScalaVersion] = Some(IgnoreScala2)

  override val isExtensionMethods: Boolean = true

  check(
    "basic",
    """|object A:
       |  extension (num: Int) def incr = ???
       |
       |def main = 1.<<incr>>
       |""".stripMargin,
    """|A
       |""".stripMargin,
  )

  checkEdit(
    "basic-edit",
    """|object A:
       |  extension (num: Int) def incr = ???
       |
       |def main = 1.<<incr>>
       |""".stripMargin,
    """|import A.incr
       |object A:
       |  extension (num: Int) def incr = ???
       |
       |def main = 1.incr
       |""".stripMargin,
  )
}
