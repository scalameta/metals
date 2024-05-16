package tests.codeactions

import scala.meta.internal.metals.codeactions.PatternMatchRefactor

import org.eclipse.lsp4j.CodeAction

class ConvertPatternMatchLspSuite
    extends BaseCodeActionLspSuite(
      "convertPatternMatch"
    ) {

  val filterAction: CodeAction => Boolean = { (act: CodeAction) =>
    act.getTitle() == PatternMatchRefactor.convertPatternMatch
  }

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
       |""".stripMargin,
  )

  check(
    "with-placeholder-after-brace",
    """|object Main {
       |  var x = 0
       |  List(1, 2).foreach {
       |    _ <<>>match {
       |      case 1 => {
       |        x = 1
       |        Nil
       |      }
       |     case 2 =>
       |      {
       |       x = 2; Nil
       |      }
       |      case 3 => x = 3; Nil
       |   }
       |  }
       |}
       |""".stripMargin,
    s"""|${PatternMatchRefactor.convertPatternMatch}
        |""".stripMargin,
    """|object Main {
       |  var x = 0
       |  List(1, 2).foreach {
       |    case 1 => {
       |      x = 1
       |      Nil
       |    }
       |    case 2 =>
       |    {
       |     x = 2; Nil
       |    }
       |    case 3 => x = 3; Nil
       |  }
       |}
       |""".stripMargin,
    filterAction = filterAction,
  )

  checkNoAction(
    "dont-convert-anonymous-function",
    """|object Main {
       |  List(1,2).map {
       |    <<case 1 => 1>>
       |    case 2 => 2
       |  }
       |}
       |""".stripMargin,
    filterAction = filterAction,
  )

}
