package tests.codeactions

import scala.meta.internal.metals.codeactions.PatternMatchRefactor

class PatternMatchRefactorLspSuite
    extends BaseCodeActionLspSuite("implementAbstractMembers") {

  check(
    "with-placeholder",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { 
       |    _ match {
       |      case 1 => {
       |        x = <<1>>
       |        Nil
       |      }
       |      case 2 =>
       |      {
       |        x = 2; Nil
       |      }
       |      case 3 => x = 3; Nil
       |    }
       |  }
       |}
       |""".stripMargin,
    PatternMatchRefactor.convertPatternMatch,
    """|object Main {
       |  var x = 0
       |  List(1,2).map { 
       |    case 1 => {
       |      x = 1
       |      Nil
       |    }
       |    case 2 =>
       |    {
       |      x = 2; Nil
       |    }
       |    case 3 => x = 3; Nil
       |  }
       |}
       |""".stripMargin
  )

  check(
    "with-placeholder-after-brace",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { _ <<>>match {
       |    case 1 => {
       |      x = 1
       |      Nil
       |    }
       |    case 2 =>
       |    {
       |      x = 2; Nil
       |    }
       |    case 3 => x = 3; Nil
       |  }}
       |}
       |""".stripMargin,
    PatternMatchRefactor.convertPatternMatch,
    """|object Main {
       |  var x = 0
       |  List(1,2).map { 
       |    case 1 => {
       |      x = 1
       |      Nil
       |    }
       |    case 2 =>
       |    {
       |      x = 2; Nil
       |    }
       |    case 3 => x = 3; Nil
       |  }
       |}
       |""".stripMargin
  )

  checkNoAction(
    "dont-convert-anonymous-function",
    """|object Main {
       |  List(1,2).map {
       |    <<case 1 => 1>>
       |    case 2 => 2
       |  }
       |}
       |""".stripMargin
  )

}
