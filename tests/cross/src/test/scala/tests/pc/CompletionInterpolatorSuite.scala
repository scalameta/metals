package tests.pc

import tests.BaseCompletionSuite
import tests.pc.CrossTestEnrichments._

class CompletionInterpolatorSuite extends BaseCompletionSuite {

  checkEdit(
    "string",
    """|object Main {
       |  val myName = ""
       |  def message = "Hello $myNam@@, you are welcome"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  def message = s"Hello $myName$0, you are welcome"
       |}
       |""".stripMargin,
    filterText = "myName",
  )

  checkEdit(
    "string1",
    """|object Main {
       |  val myName = ""
       |  def message = "$myNam@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val myName = ""
       |  def message = s"$myName$0"
       |}
       |""".stripMargin,
    filterText = "myName",
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
       |  def message = s"${myName$0}me"
       |}
       |""".stripMargin,
    filterText = "myName",
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
       |  def message = s'''${myName$0}me'''
       |}
       |""".stripMargin.triplequoted,
    filterText = "myName",
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
       |    |${myName$0}me
       |    |'''.stripMargin
       |}
       |""".stripMargin.triplequoted,
    filterText = "myName".triplequoted,
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
       |  s"$myName$0 $$"
       |}
       |""".stripMargin.triplequoted,
    filterText = "myName",
  )

  checkEdit(
    "not-escape-twice",
    """|object Main {
       |  val myName = ""
       |  s"$myNam@@ $$"
       |}
       |""".stripMargin.triplequoted,
    """|object Main {
       |  val myName = ""
       |  s"$myName$0 $$"
       |}
       |""".stripMargin.triplequoted,
    filterText = "myName",
    compat = Map(
      "2" ->
        """|object Main {
           |  val myName = ""
           |  s"$myName $$"
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "escape-ident",
    """|object Main {
       |  val myName = ""
       |  "Say $myName is $myNam@@"
       |}
       |""".stripMargin.triplequoted,
    """|object Main {
       |  val myName = ""
       |  s"Say $$myName is $myName$0"
       |}
       |""".stripMargin.triplequoted,
  )

  check(
    "interpolator",
    """|object Main {
       |  val myName = ""
       |  def message = s"Hello $myNam@@, you are welcome"
       |}
       |""".stripMargin,
    """|myName: String
       |""".stripMargin,
  )

  checkEdit(
    "interpolator-in-object",
    """|object Outer {
       |  private def method = {
       |    object Test {
       |      val hello: String = "1"
       |      s"$hello.toStri@@  $$"
       |    }
       |  }
       |}
       |""".stripMargin,
    """|object Outer {
       |  private def method = {
       |    object Test {
       |      val hello: String = "1"
       |      s"${hello.toString()$0}  $$"
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "negative",
    """|object Main {
       |  "$1@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative1",
    """|object Main {
       |  "$ @@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative2",
    """|object Main {
       |  "$-@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative3",
    """|object Main {
       |  "$-@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative4",
    """|object Main {
       |  "$hello-@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative5",
    """|object Main {
       |  "$-hello@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "negative6",
    """|object Main {
       |  "$he-llo@@"
       |}
       |""".stripMargin,
    "",
  )

  check(
    "positive",
    """|object Main {
       |  val he11o = "hello"
       |  "$he11o@@"
       |}
       |""".stripMargin,
    "he11o: String",
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
       |  s"$$$myName$0"
       |}
       |""".stripMargin,
  )

  checkEdit(
    "snippet",
    """|object Main {
       |  "$identity@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"${identity($0)}"
       |}
       |""".stripMargin,
  )

  checkEdit(
    "snippet2",
    """|object Main {
       |  "$toStrin@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"${toString()$0}"
       |}
       |""".stripMargin,
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
       |  s"$empty$0"
       |}
       |""".stripMargin,
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
       |  s"${myName$0}"
       |}
       |""".stripMargin,
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
    topLines = Some(2),
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
    topLines = Some(2),
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
       |""".stripMargin,
  )

  check(
    "member-label".tag(
      IgnoreScalaVersion.forRangeUntil(
        "3.2.0-RC1",
        "3.2.1-RC1",
      )
    ),
    """|object Main {
       |  
       |  s"Hello $List.e@@ "
       |}
       |""".stripMargin,
    """|empty[A]: List[A]
       |equals(x$1: Object): Boolean
       |""".stripMargin,
    compat = Map(
      "2.12" ->
        """|empty[A]: List[A]
           |equals(x$1: Any): Boolean
           |""".stripMargin,
      "3" ->
        """|empty[A]: List[A]
           |equals(x$0: Any): Boolean
           |""".stripMargin,
    ),
    topLines = Some(6),
    includeDetail = false,
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
       |""".stripMargin,
  )

  checkEdit(
    "member2"
      .tag(
        IgnoreScalaVersion.forRangeUntil(
          "3.2.0-RC1",
          "3.2.1-RC1",
        )
      ),
    """|object Main {
       |  s"Hello $Main.toStr@@!"
       |}
       |""".stripMargin,
    """|object Main {
       |  s"Hello ${Main.toString()$0}!"
       |}
       |""".stripMargin,
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
    topLines = Some(2),
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
       |""".stripMargin,
  )

  checkEdit(
    "member-multiple",
    """|object Main {
       |  val abc = ""
       |  val dfg = ""
       |  s"Hello $abc.toStrin@@ from ${dfg.toString()}!"
       |}
       |""".stripMargin,
    """|object Main {
       |  val abc = ""
       |  val dfg = ""
       |  s"Hello ${abc.toString()$0} from ${dfg.toString()}!"
       |}
       |""".stripMargin,
  )

  checkEdit(
    "member-multiple2",
    """|object Main {
       |  val abc = ""
       |  val dfg = ""
       |  s"Hello $dfg $abc.toStrin@@ from ${dfg.toString()}!"
       |}
       |""".stripMargin,
    """|object Main {
       |  val abc = ""
       |  val dfg = ""
       |  s"Hello $dfg ${abc.toString()$0} from ${dfg.toString()}!"
       |}
       |""".stripMargin,
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
    topLines = Some(2),
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
    topLines = Some(2),
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
    topLines = Some(2),
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
    filter = s => s.contains("member"),
  )

  checkEditLine(
    "closing-brace",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """"Hello ${hell@@}"""".stripMargin,
    """s"Hello ${hello$0}"""".stripMargin,
  )

  checkEditLine(
    "closing-brace-negative",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """"Hello ${hell@@o}"""".stripMargin,
    """s"Hello ${hello$0}o}"""".stripMargin,
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
    filter = _.contains("a: Int"),
  )

  checkEditLine(
    "token-error",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """s"Hello $@@"""".stripMargin,
    """s"Hello $hello"""".stripMargin,
    filter = _.contains("hello"),
  )

  checkEditLine(
    "brace-token-error-pos",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """s"Hello ${@@"""".stripMargin,
    """s"Hello ${hello"""".stripMargin,
    filter = _.contains("hello"),
  )

  checkEditLine(
    "brace-token-error",
    """|object Main {
       |  val hello = ""
       |  ___
       |}
       |""".stripMargin,
    """s"Hello ${@@}"""".stripMargin,
    """s"Hello ${hello}"""".stripMargin,
    filter = _.contains("hello"),
  )

  checkEdit(
    "backtick",
    """|object Main {
       |  val `type` = 42
       |  "Hello $type@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val `type` = 42
       |  s"Hello ${`type`$0}"
       |}
       |""".stripMargin,
    filterText = "type",
  )

  checkEdit(
    "backtick2",
    """|object Main {
       |  val `hello world` = 42
       |  "Hello $hello@@"
       |}
       |""".stripMargin,
    """|object Main {
       |  val `hello world` = 42
       |  s"Hello ${`hello world`$0}"
       |}
       |""".stripMargin,
    filterText = "hello world",
  )

  checkEdit(
    "auto-imports",
    """|object Main {
       |  "this is an interesting $Paths@@"
       |}
       |""".stripMargin,
    """|import java.nio.file.Paths
       |object Main {
       |  s"this is an interesting $Paths$0"
       |}
       |""".stripMargin,
  )

  checkEdit(
    "auto-imports-prefix",
    """|
       |class Paths
       |object Main {
       |  "this is an interesting $Paths@@"
       |}
       |""".stripMargin,
    """|import java.nio.file
       |
       |class Paths
       |object Main {
       |  s"this is an interesting ${file.Paths$0}"
       |}
       |""".stripMargin,
    assertSingleItem = false,
    // Scala 3 has an additional Paths() completion
    itemIndex = if (scalaVersion.startsWith("2")) 1 else 2,
    compat = Map(
      "3" ->
        """|class Paths
           |object Main {
           |  s"this is an interesting {java.nio.file.Paths}"
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "auto-imports-prefix-with-interpolator".tag(
      IgnoreScalaVersion.forRangeUntil(
        "3.2.0-RC1",
        "3.2.1-RC1",
      )
    ),
    """|
       |class Paths
       |object Main {
       |  s"this is an interesting $Paths@@"
       |}
       |""".stripMargin,
    """|import java.nio.file
       |
       |class Paths
       |object Main {
       |  s"this is an interesting ${file.Paths}"
       |}
       |""".stripMargin,
    // Scala 3 has an additional Paths object completion
    itemIndex = if (scalaVersion.startsWith("2")) 0 else 1,
    assertSingleItem = false,
    compat = Map(
      "3" ->
        """|class Paths
           |object Main {
           |  s"this is an interesting ${java.nio.file.Paths}"
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "extension".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |def aaa = 123
       |def main = s" $aaa.inc@@"
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.incr
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |def aaa = 123
       |def main = s" ${aaa.incr$0}"
       |""".stripMargin,
    // simulate issues with VS Code
    filterText = "aaa.incr",
  )

  checkEdit(
    "extension2".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def aaa = 123
       |def main = s"  $aaa.pl@@"
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.plus
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def aaa = 123
       |def main = s"  ${aaa.plus($0)}"
       |""".stripMargin,
    filterText = "aaa.plus",
  )

  check(
    "filter-by-type".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |  extension (str: String)
       |    def identity: String = str
       |
       |val foo = "foo"
       |def main = s" $foo.i@@"
       |""".stripMargin,
    """|identity: String (extension)
       |""".stripMargin, // incr won't be available
    filter = _.contains("(extension)"),
  )

  checkEdit(
    "apply-method".tag(IgnoreScala2),
    """|object Main {
       |  val a = "$ListBuf@@""
       |}""".stripMargin,
    """|import scala.collection.mutable.ListBuffer
       |object Main {
       |  val a = s"${ListBuffer($0)}""
       |}""".stripMargin,
    filter = _.contains("[A]"),
  )

}
