package tests.codeactions

class ImplementAbstractMembersScala3LspSuite
    extends BaseCodeActionLspSuite("implementAbstractScala3Members") {

  check(
    // it should be a syntax error
    "braceless-noaction",
    """|package a
       |
       |object A:
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  class <<Concrete>> extends Base:
       |""".stripMargin,
    "",
    """|package a
       |
       |object A:
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  class Concrete extends Base:
       |""".stripMargin,
    expectError = true,
    expectNoDiagnostics = false
  )

}
