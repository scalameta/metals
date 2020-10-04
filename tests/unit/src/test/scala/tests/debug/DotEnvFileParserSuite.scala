package tests.debug

import scala.meta.internal.metals.debug.DotEnvFileParser._

import tests.BaseSuite

class DotEnvFileParserSuite extends BaseSuite {
  test("parse empty values") {
    assertDiffEqual(parse("KEY="), Map("KEY" -> ""))
  }

  test("parse values separated by :") {
    assertDiffEqual(parse("KEY:value"), Map("KEY" -> "value"))
  }

  test("parse values separated by : with spaces around separator") {
    val expected = Map("KEY" -> "value")
    assertDiffEqual(parse("KEY:  value"), expected)
    assertDiffEqual(parse("KEY  :value"), expected)
    assertDiffEqual(parse("KEY  :  value"), expected)
  }

  test("parse unquoted values") {
    assertDiffEqual(parse("KEY=value"), Map("KEY" -> "value"))
  }

  test("strip unquoted values") {
    assertDiffEqual(parse("KEY=value  "), Map("KEY" -> "value"))
  }

  test("parse unquoted values with spaces around separator") {
    val expected = Map("KEY" -> "value")
    assertDiffEqual(parse("KEY  =value"), expected)
    assertDiffEqual(parse("KEY=  value"), expected)
    assertDiffEqual(parse("KEY  =  value"), expected)
  }

  test("parse unquoted values with leading and trailing spaces") {
    val expected = Map("KEY" -> "value")
    assertDiffEqual(parse("  KEY=value"), expected)
    assertDiffEqual(parse("KEY=value  "), expected)
    assertDiffEqual(parse("  KEY=value  "), expected)
  }

  test("parse single quoted values") {
    assertDiffEqual(parse("KEY='value'"), Map("KEY" -> "value"))
  }

  test("strip single quoted values") {
    val expected = Map("KEY" -> "value")
    assertDiffEqual(parse("KEY='  value'"), expected)
    assertDiffEqual(parse("KEY='value  '"), expected)
    assertDiffEqual(parse("KEY=' value  '"), expected)
  }

  test("parse double quoted values") {
    assertDiffEqual(parse("""KEY="value""""), Map("KEY" -> "value"))
  }

  test("strip double quoted values") {
    val expected = Map("KEY" -> "value")
    assertDiffEqual(parse("""KEY="  value""""), expected)
    assertDiffEqual(parse("""KEY="value  """"), expected)
    assertDiffEqual(parse("""KEY="  value  """"), expected)
  }

  test("parse escaped double quotes") { ??? }

  test("parse export keyword") { ??? }

  test("ignore lines with no assignment") { ??? }

  test("ignore lines with comments") { ??? }

  test("ignore empty lines") { ??? }

  test("ignore inline comments") { ??? }

  test("allow # in quoted values") { ??? }

  test("parse multi-line single quoted values") { ??? }

  test("parse multi-line double quoted values") { ??? }

  test("parse multiple values") { ??? }

  test("parse multiple values with mixed quoting") { ??? }

  test("parse multiple values with mixed quoting") { ??? }

  test("exclude variables with . in the name") { ??? }

  test("exclude variables with - in the name") { ??? }
}
