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
    filterText = "\"Hello $myName"
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
    filterText = "\"$myName"
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
    filterText = "\"$myName"
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
    filterText = "'''$myName".triplequoted
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
    filterText = "'''\n    |$myName".triplequoted
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
    filterText = "\"$myName"
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
       |  val myName = "name"
       |  "$$myNam@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = "name"
       |  s"\$\$\$myName$0"
       |}
       |""".stripMargin
  )

  checkEdit(
    "snippet",
    """|object Main {
       |  "$identity@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"\${identity($0)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "snippet2",
    """|object Main {
       |  "$toStrin@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"\${toString()$0}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "snippet3",
    """|object Main {
       |  def empty: Boolean = true
       |  "$empty@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  def empty: Boolean = true
       |  s"\$empty$0"
       |}
       |""".stripMargin
  )

  checkEdit(
    "brace",
    """|object Main {
       |  val myName = ""
       |  "${myNa@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  s"\${myName$0}"
       |}
       |""".stripMargin
  )

  check(
    "empty",
    """|object Main {
       |  locally {
       |    val a = ""
       |    val b = 42
       |    "$@@"
       |  }
       |}
       |""".stripMargin,
    """|b: Int
       |a: String
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "empty-brace",
    """|object Main {
       |  locally {
       |    val a = ""
       |    val b = 42
       |    "${@@"
       |  }
       |}
       |""".stripMargin,
    """|b: Int
       |a: String
       |""".stripMargin,
    topLines = Some(2)
  )

  checkEdit(
    "member",
    """|object Main {
       |  def member = 42
       |  s"Hello $Main.membe@@!"
       |}
       |""".stripMargin,
    """|object Main {
       |  def member = 42
       |  s"Hello ${Main.member$0}!"
       |}
       |""".stripMargin
  )

  checkEdit(
    "member1",
    """|object Main {
       |  def method(arg: Int) = 42
       |  s"Hello $Main.meth@@!"
       |}
       |""".stripMargin,
    """|object Main {
       |  def method(arg: Int) = 42
       |  s"Hello ${Main.method($0)}!"
       |}
       |""".stripMargin
  )

  checkEdit(
    "member2",
    """|object Main {
       |  s"Hello $Main.toStr@@!"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"Hello ${Main.toString()$0}!"
       |}
       |""".stripMargin
  )

  check(
    "member3",
    """|object Main {
       |  val a = ""
       |  val b = 42
       |  s"Hello $Main.@@!"
       |}
       |""".stripMargin,
    """|a: String
       |b: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  checkEdit(
    "member-backtick",
    """|object Main {
       |  val `type` = ""
       |  s"Hello $Main.type@@!"
       |}
       |""".stripMargin,
    """|object Main {
       |  val `type` = ""
       |  s"Hello ${Main.`type`$0}!"
       |}
       |""".stripMargin
  )

  check(
    "member-f",
    """|object Main {
       |  val a = ""
       |  val b = 42
       |  f"Hello $Main.@@!"
       |}
       |""".stripMargin,
    """|a: String
       |b: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "member-raw",
    """|object Main {
       |  val a = ""
       |  val b = 42
       |  raw"Hello $Main.@@!"
       |}
       |""".stripMargin,
    """|a: String
       |b: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "member-unknown",
    """|object Main {
       |  val a = ""
       |  val b = 42
       |  implicit class XtensionStringContext(c: StringContext) {
       |    def unknown(args: Any*): String = ""
       |  }
       |  unknown"Hello $Main.@@!"
       |}
       |""".stripMargin,
    """|a: String
       |b: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "member-multiline",
    """|object Main {
       |  val member = ""
       |  s'''
       |    Hello $Main.memb@@!
       |  '''
       |}
       |""".stripMargin.triplequoted,
    """|member: String
       |""".stripMargin,
    filterText = "$Main.member"
  )

  checkEditLine(
    "closing-brace",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """"Hello ${hell@@}"""".stripMargin,
    """s"Hello \${hello$0}"""".stripMargin
  )

  checkEditLine(
    "closing-brace-negative",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """"Hello ${hell@@o}"""".stripMargin,
    """s"Hello \${hello$0}o}"""".stripMargin
  )

  // See https://github.com/scalameta/metals/issues/608
  // Turns out this bug was accidentally fixed by limiting snippets to only
  // when creating new expressions, inside existing code we don't insert ($0) snippets.
  checkEditLine(
    "existing-interpolator-snippet",
    """|object Main {
       |  val hello = ""
       |  def helloMethod(a: Int) = ""
       |  ___
       |}
       |""".stripMargin,
    """s"Hello $hello@@"""".stripMargin,
    """s"Hello $helloMethod"""".stripMargin,
    filter = _.contains("a: Int")
  )

}
