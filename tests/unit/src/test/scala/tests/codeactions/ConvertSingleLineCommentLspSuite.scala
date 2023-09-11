package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertCommentCodeAction

class ConvertSingleLineCommentLspSuite
    extends BaseCodeActionLspSuite("convertComment") {

  check(
    "convert single line comment to multiline",
    """val a = 1
      |
      |// <<>>comment middle
      |
      |val b = 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |
      |/* comment middle */
      |
      |val b = 2
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to multiline if it starts with a whitespace",
    """val a = 1
      |
      |   // <<>>comment middle
      |
      |val b = 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |
      |   /* comment middle */
      |
      |val b = 2
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to multiline if it starts with var decl",
    """val a = 1
      |
      |val b = 2 // <<>>comment middle
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |
      |val b = 2 /* comment middle */
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to multiline if it is defined between method params",
    """def foo(
      |  name: String, // another comment
      |  age: Int, //<<>> imporant comment
      |  amount: Int
      |)
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """def foo(
      |  name: String, // another comment
      |  age: Int, /* imporant comment */
      |  amount: Int
      |)""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert the whole comment chunk expanded in both ways",
    """val a = 1
      |// comment start 1
      |// comment start 2
      |// <<>>comment middle
      |// comment end 1
      |// comment end 2
      |val b = 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |/* comment start 1
      | * comment start 2
      | * comment middle
      | * comment end 1
      | * comment end 2 */
      |val b = 2
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert the whole comment chunk expanded in both ways -- additional white spaces",
    """val a = 1
      |// comment start 1
      |  // comment start 2
      |// <<>>comment middle
      |// comment end 1
      |  // comment end 2
      |val b = 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |/* comment start 1
      | * comment start 2
      | * comment middle
      | * comment end 1
      | * comment end 2 */
      |val b = 2
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "should show action for first line in the file",
    """// <<>>comment middle""",
    ConvertCommentCodeAction.Title,
    """/* comment middle */""",
    fileName = "script.sc",
  )

  checkNoAction(
    "should not show action for single-line multiline comment",
    """|val a = 1
       |/* <<>>comment middle */
       |val b = 2""".stripMargin,
    fileName = "script2.sc",
  )
}
