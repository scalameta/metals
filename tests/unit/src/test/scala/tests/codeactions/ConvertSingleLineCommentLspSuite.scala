package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertCommentCodeAction

class ConvertSingleLineCommentLspSuite
    extends BaseCodeActionLspSuite("convertComment") {

  check(
    "convert single line comment to block comment",
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
    "convert single line comment to block comment if it starts with a whitespace",
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
    "convert single line comments to block comment when part of it is indented",
    """// comment start
      |   // <<>>comment middle
      |
      |val b = 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """/* comment start
      | * comment middle */
      |
      |val b = 2
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "mixed style comments gets merged",
    """// <<>>comment start
      |/* comment middle */
      |
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """/* comment start
      | * comment middle */
      |
      |""".stripMargin,
    fileName = "script.sc",
  )

  checkNoAction(
    "show no action when line comment is inside block comment",
    """|
       |/* comment //<<>> start */
       |
       |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to block comment if it starts with var decl",
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
    "convert single line comment to block comment if it includes a block comment",
    """val a = 1
      |
      |// <<>> /* comment middle */
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |
      |/* /* comment middle */ */
      |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to block comment if the line starts with a block comment",
    """val a = 1
      |
      |/* comment start */ //<<>> comment end
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """val a = 1
      |
      |/* comment start
      | * comment end */
      |""".stripMargin,
    fileName = "script.sc",
  )

  checkNoAction(
    "show no action when cursor is before line comment",
    """|
       |<<>>// start 
       |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert single line comment to block comment if it is defined between method params",
    """def foo(
      |  name: String, // another comment
      |  age: Int, //<<>> important comment
      |  amount: Int
      |)
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """def foo(
      |  name: String, // another comment
      |  age: Int, /* important comment */
      |  amount: Int
      |)""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "convert the whole comment cluster expanded in both ways",
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
    "convert the whole comment cluster expanded in both ways -- additional white spaces",
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
    "convert the whole comment cluster expanded in both ways -- mixed style comments",
    """val a = 1
      |// comment start 1
      |  /* comment start 2 */
      |// <<>>comment middle
      |/* comment end 1 */
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
    "convert the whole comment cluster expanded in both ways -- mixed with other tokens",
    """val a = 1
      |// comment start 1
      |  /* comment start 2 */ val b = 1
      |// <<>>comment middle
      |/* comment end 1 */ val c = 2
      |  // comment end 2
      |""".stripMargin,
    ConvertCommentCodeAction.Title,
    """|val a = 1
       |// comment start 1
       |  /* comment start 2 */ val b = 1
       |/* comment middle
       | * comment end 1 */ val c = 2
       |  // comment end 2
       |""".stripMargin,
    fileName = "script.sc",
  )

  check(
    "show action if line comment is the first line in the file",
    """// <<>>comment middle""",
    ConvertCommentCodeAction.Title,
    """/* comment middle */""",
    fileName = "script.sc",
  )

  checkNoAction(
    "should not show action when cursor is after block comment",
    """|val a = 1
       |/* comment middle */ <<>> 
       |val b = 2""".stripMargin,
    fileName = "script2.sc",
  )
}
