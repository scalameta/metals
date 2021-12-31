package tests.pc

import tests.BaseCompletionSuite

class CompletionErrorSuite extends BaseCompletionSuite {

  check(
    "issue-3421",
    """|object Main{
       |  def thing() = thi@@
       |  def run() = {
       |    val thing = ???
       |  }
       |}
       |""".stripMargin,
    "thing(): Any",
    topLines = Some(1),
    compat = Map(
      "3" -> "thing: Any"
    )
  )

  check(
    "issue-3421-match",
    """|
       |trait Dependency
       |object InvalidDependency extends Dependency
       |object Main{
       |  def exists(dep: Dependency) = {
       |    deps match {
       |      case invalid @ InvalidDependency => println(inv@@)
       |    }
       |  }
       |}
       |""".stripMargin,
    "invalid: Any",
    topLines = Some(1),
    compat = Map(
      "2.12" -> "invalid: InvalidDependency.type"
    )
  )

}
