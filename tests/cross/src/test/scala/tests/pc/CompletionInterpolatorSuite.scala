package tests.pc

import tests.BaseCompletionSuite
import tests.pc.CrossTestEnrichments._

object CompletionInterpolatorSuite extends BaseCompletionSuite {

  checkEdit(
    "string",
    """|object Main {
       |  val myName = ""
       |  def message = "Hello $myNam@@, you are welcome"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  def message = s"Hello \$myName$0, you are welcome"
       |}
       |""".stripMargin,
    filterText = "\"Hello $myNam"
  )

  checkEdit(
    "string1",
    s"""|object Main {
        |  val myName = ""
        |  def message = "$$myNam@@"
        |}
        |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  def message = s"\$myName$0"
       |}
       |""".stripMargin,
    filterText = "\"$myNam"
  )

  checkEdit(
    "string2",
    s"""|object Main {
        |  val myName = ""
        |  def message = "$$myNa@@me"
        |}
        |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  def message = s"\${myName$0}me"
       |}
       |""".stripMargin,
    filterText = "\"$myNa"
  )

  checkEdit(
    "multiline",
    """|object Main {
       |  val myName = ""
       |  def message = '''$myNa@@me'''
       |}
       |""".stripMargin.triplequoted,
    """|object Main {
       |  val myName = ""
       |  def message = s'''\${myName$0}me'''
       |}
       |""".stripMargin.triplequoted,
    filterText = "'''$myNa".triplequoted
  )

  checkEdit(
    "multiline1",
    """|object Main {
       |  val myName = ""
       |  def message = '''
       |    |$myNa@@me
       |    |'''.stripMargin
       |}
       |""".stripMargin.triplequoted,
    """|object Main {
       |  val myName = ""
       |  def message = s'''
       |    |\${myName$0}me
       |    |'''.stripMargin
       |}
       |""".stripMargin.triplequoted,
    filterText = "'''\n    |$myNa".triplequoted
  )

  checkEdit(
    "escape",
    """|object Main {
       |  val myName = ""
       |  "$myNam@@ $"
       |}
       |""".stripMargin.triplequoted,
    """|object Main {
       |  val myName = ""
       |  s"\$myName$0 \$\$"
       |}
       |""".stripMargin.triplequoted,
    filterText = "\"$myNam"
  )

  check(
    "interpolator",
    """|object Main {
       |  val myName = ""
       |  def message = s"Hello $myNam@@, you are welcome"
       |}
       |""".stripMargin,
    """|myName: String
       |""".stripMargin
  )

  check(
    "negative",
    """|object Main {
       |  "$1@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative1",
    """|object Main {
       |  "$ @@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative2",
    """|object Main {
       |  "$-@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative3",
    """|object Main {
       |  "$-@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative4",
    """|object Main {
       |  "$hello-@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative5",
    """|object Main {
       |  "$-hello@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "negative6",
    """|object Main {
       |  "$he-llo@@"
       |}
       |""".stripMargin,
    ""
  )

  check(
    "positive",
    """|object Main {
       |  val he11o = "hello"
       |  "$he11o@@"
       |}
       |""".stripMargin,
    "he11o: String"
  )

  checkEdit(
    "positive1",
    """|object Main {
       |  val myName = "displayName"
       |  "$$myNam@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = "displayName"
       |  s"\$\$\$myName$0"
       |}
       |""".stripMargin
  )

}
