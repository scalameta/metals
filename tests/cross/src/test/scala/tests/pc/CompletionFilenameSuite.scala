package tests.pc

import tests.BaseCompletionSuite

object CompletionFilenameSuite extends BaseCompletionSuite {

  check(
    "class",
    """|
       |class M@@
       |""".stripMargin,
    "class Main",
    filename = "Main.scala"
  )

  check(
    "companion-class",
    """|object Main
       |class M@@
       |""".stripMargin,
    "class Main",
    filename = "Main.scala"
  )

  check(
    "companion-trait",
    """|object Main
       |trait M@@
       |""".stripMargin,
    "trait Main",
    filename = "Main.scala"
  )

  check(
    "companion-object",
    """|trait Main
       |object M@@
       |""".stripMargin,
    "object Main",
    filename = "Main.scala"
  )

  check(
    "companion-object2",
    """|class Main
       |object M@@
       |""".stripMargin,
    "object Main",
    filename = "Main.scala"
  )

  check(
    "duplicate",
    """|object Main
       |class Main
       |class M@@
       |""".stripMargin,
    "",
    filename = "Main.scala"
  )

  check(
    "inner",
    """|object Outer {
       |  class M@@
       |}
       |""".stripMargin,
    "",
    filename = "Main.scala"
  )

  check(
    "fuzzy",
    """|
       |class MDataSer@@
       |""".stripMargin,
    "class MyDatabaseService",
    filename = "MyDatabaseService.scala"
  )

  check(
    "path",
    """|
       |class Us@@
       |""".stripMargin,
    "class User",
    filename = "foo/User.scala"
  )

  check(
    "object",
    """|
       |object Us@@
       |""".stripMargin,
    "object User",
    filename = "User.scala"
  )

  check(
    "trait",
    """|
       |trait Us@@
       |""".stripMargin,
    "trait User",
    filename = "User.scala"
  )

  check(
    "type-duplicate",
    """|
       |class User
       |trait Us@@
       |""".stripMargin,
    "",
    filename = "User.scala"
  )

  check(
    "term-duplicate",
    """|
       |object User
       |object Us@@
       |""".stripMargin,
    "",
    filename = "User.scala"
  )
}
