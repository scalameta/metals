package tests.debug

import scala.meta.internal.metals.debug.DotEnvFileParser._

import tests.BaseSuite

class DotEnvFileParserSuite extends BaseSuite {
  val keyValue: Map[String, String] = Map("KEY" -> "value")

  test("parse empty values") {
    assertDiffEqual(parse("KEY="), Map("KEY" -> ""))
  }

  test("parse values separated by :") {
    assertDiffEqual(parse("KEY:value"), keyValue)
  }

  test("parse values separated by : with spaces around separator") {
    assertDiffEqual(parse("KEY:  value"), keyValue)
    assertDiffEqual(parse("KEY  :value"), keyValue)
    assertDiffEqual(parse("KEY  :  value"), keyValue)
  }

  test("parse unquoted values") {
    assertDiffEqual(parse("KEY=value"), keyValue)
  }

  test("strip unquoted values") {
    assertDiffEqual(parse("KEY=value  "), keyValue)
  }

  test("parse unquoted values with spaces around separator") {
    assertDiffEqual(parse("KEY  =value"), keyValue)
    assertDiffEqual(parse("KEY=  value"), keyValue)
    assertDiffEqual(parse("KEY  =  value"), keyValue)
  }

  test("parse unquoted values with leading and trailing spaces") {
    assertDiffEqual(parse("  KEY=value"), keyValue)
    assertDiffEqual(parse("KEY=value  "), keyValue)
    assertDiffEqual(parse("  KEY=value  "), keyValue)
  }

  test("parse single quoted values") {
    assertDiffEqual(parse("KEY='value'"), keyValue)
  }

  test("strip single quoted values") {
    assertDiffEqual(parse("KEY='  value'"), keyValue)
    assertDiffEqual(parse("KEY='value  '"), keyValue)
    assertDiffEqual(parse("KEY=' value  '"), keyValue)
  }

  test("parse double quoted values") {
    assertDiffEqual(parse("""KEY="value""""), keyValue)
  }

  test("strip double quoted values") {
    assertDiffEqual(parse("""KEY="  value""""), keyValue)
    assertDiffEqual(parse("""KEY="value  """"), keyValue)
    assertDiffEqual(parse("""KEY="  value  """"), keyValue)
  }

  test("parse escaped double quotes") {
    assertDiffEqual(parse("""KEY="\"quoted\"""""), Map("KEY" -> "\"quoted\""))
  }

  test("parse export keyword") {
    assertDiffEqual(parse("export KEY=value"), keyValue)
  }

  test("ignore lines with no assignment") {
    assertDiffEqual(parse("KEY"), Map.empty)
  }

  test("ignore lines with comments") {
    assertDiffEqual(parse("#KEY=value"), Map.empty)
  }

  test("ignore empty lines") {
    assertDiffEqual(parse(" \nKEY=value"), keyValue)
  }

  test("allow # and spaces in unquoted values") {
    assertDiffEqual(
      parse("KEY=v a l u e # not a comment"),
      Map("KEY" -> "v a l u e # not a comment")
    )
  }

  test("ignore inline comments after single quoted values") {
    assertDiffEqual(parse("KEY='value' # comment"), keyValue)
  }

  test("ignore inline comments after double quoted values") {
    assertDiffEqual(parse("""KEY="value" # comment"""), keyValue)
  }

  test("allow # in quoted values") {
    assertDiffEqual(parse("""export KEY="v#l#e""""), Map("KEY" -> "v#l#e"))
  }

  test("parse multi-line single quoted values") {
    val content = """KEY='line1
                    |line2'""".stripMargin
    assertDiffEqual(parse(content), Map("KEY" -> "line1\nline2"))
  }

  test("parse multi-line double quoted values") {
    val content = """KEY="line1
                    |line2"""".stripMargin
    assertDiffEqual(parse(content), Map("KEY" -> "line1\nline2"))
  }

  test("parse multiple values") {
    val content = """KEY=value
                    |KEY2=value2""".stripMargin
    assertDiffEqual(parse(content), Map("KEY" -> "value", "KEY2" -> "value2"))
  }

  test("parse multiple values with mixed quoting") {
    val content = """KEY=value
                    |KEY2='value2'
                    |KEY3="value3"""".stripMargin
    assertDiffEqual(
      parse(content),
      Map("KEY" -> "value", "KEY2" -> "value2", "KEY3" -> "value3")
    )
  }

  test("parse multiple multi-line values with mixed quoting") {
    val content = """KEY=value
                    |ignored
                    |KEY2='value2 line1
                    |value2 line2'
                    |KEY3="value3 line1
                    |value3 line2"""".stripMargin
    assertDiffEqual(
      parse(content),
      Map(
        "KEY" -> "value",
        "KEY2" -> "value2 line1\nvalue2 line2",
        "KEY3" -> "value3 line1\nvalue3 line2"
      )
    )
  }

  test("exclude variables with . in the name") {
    assertDiffEqual(parse("K.E.Y=value"), Map.empty)
  }

  test("exclude variables with - in the name") {
    assertDiffEqual(parse("K-E-Y=value"), Map.empty)
  }
}
