package tests

import scala.meta.internal.metals.Comment
import scala.meta.internal.metals.Function
import scala.meta.internal.metals.Identifier
import scala.meta.internal.metals.Keyword
import scala.meta.internal.metals.Literal
import scala.meta.internal.metals.Number
import scala.meta.internal.metals.Operator
import scala.meta.internal.metals.Other
import scala.meta.internal.metals.Parameter
import scala.meta.internal.metals.QuotedIdentifier
import scala.meta.internal.metals.SQLToken
import scala.meta.internal.metals.SQLTokenizer
import scala.meta.internal.metals.Whitespace

class SQLTokenizerSuite extends BaseSuite {

  test("comments") {
    val obtained =
      SQLTokenizer.tokenize("-- it's a comment\nSELECT /* as 1 */ 1")

    val expected = List[SQLToken](
      Comment("-- it's a comment", isClosed = true, isBlock = false),
      Whitespace("\n"),
      Keyword("SELECT"),
      Whitespace(" "),
      Comment("/* as 1 */", isClosed = true, isBlock = true),
      Whitespace(" "),
      Number("1"),
    )

    assertEquals(obtained, expected)
  }

  test("line-comment-continues-after-a-splice") {
    val lastToken = Comment("-- older than ", isClosed = false, isBlock = false)

    val obtained = SQLTokenizer.tokenize(" and more\nSELECT", Some(lastToken))

    val expected = List[SQLToken](
      Comment(" and more", isClosed = true, isBlock = false),
      Whitespace("\n"),
      Keyword("SELECT"),
    )

    assertEquals(obtained, expected)
  }

  test("block-comment-continues-after-a-splice") {
    val lastToken = Comment("/* a", isClosed = false, isBlock = true)

    val obtained = SQLTokenizer.tokenize("\nb */ 1", Some(lastToken))

    val expected = List[SQLToken](
      Comment("\nb */", isClosed = true, isBlock = true),
      Whitespace(" "),
      Number("1"),
    )

    assertEquals(obtained, expected)
  }

  test("unclosed-block-comment") {
    val obtained = SQLTokenizer.tokenize("SELECT /* a")

    val expected = List[SQLToken](
      Keyword("SELECT"),
      Whitespace(" "),
      Comment("/* a", isClosed = false, isBlock = true),
    )

    assertEquals(obtained, expected)
  }

  test("literal-continues-after-a-splice") {
    val lastToken = Literal("'prefix-", isClosed = false)

    val obtained = SQLTokenizer.tokenize("-suffix' AND", Some(lastToken))

    val expected = List[SQLToken](
      Literal("-suffix'", isClosed = true),
      Whitespace(" "),
      Keyword("AND"),
    )

    assertEquals(obtained, expected)
  }

  test("literal-stays-open-across-an-empty-part") {
    val lastToken = Literal("'a", isClosed = false)

    val obtained = SQLTokenizer.tokenize("", Some(lastToken))

    val expected = List[SQLToken](Literal("", isClosed = false))

    assertEquals(obtained, expected)
  }

  test("parameters") {
    val obtained = SQLTokenizer.tokenize("@ids, @@session_var")

    val expected = List[SQLToken](
      Parameter("@ids"),
      Other(","),
      Whitespace(" "),
      Parameter("@@session_var"),
    )

    assertEquals(obtained, expected)
  }

  test("postgresql-at-operators-are-not-parameters") {
    val obtained = SQLTokenizer.tokenize("tsv @@to_tsquery('a') AND a <@b")

    val expected = List[SQLToken](
      Identifier("tsv"),
      Whitespace(" "),
      Operator("@@"),
      Function("to_tsquery"),
      Other("("),
      Literal("'a'", isClosed = true),
      Other(")"),
      Whitespace(" "),
      Keyword("AND"),
      Whitespace(" "),
      Identifier("a"),
      Whitespace(" "),
      Operator("<@"),
      Identifier("b"),
    )

    assertEquals(obtained, expected)
  }

  test("quoted-identifiers") {
    val obtained = SQLTokenizer.tokenize("`project.table` \"a--b\"")

    val expected = List[SQLToken](
      QuotedIdentifier("`project.table`", isClosed = true, quote = '`'),
      Whitespace(" "),
      QuotedIdentifier("\"a--b\"", isClosed = true, quote = '"'),
    )

    assertEquals(obtained, expected)
  }

  test("quoted-identifier-continues-after-a-splice") {
    val lastToken = QuotedIdentifier("`", isClosed = false, quote = '`')

    val obtained = SQLTokenizer.tokenize(".table` AS t", Some(lastToken))

    val expected = List[SQLToken](
      QuotedIdentifier(".table`", isClosed = true, quote = '`'),
      Whitespace(" "),
      Keyword("AS"),
      Whitespace(" "),
      Identifier("t"),
    )

    assertEquals(obtained, expected)
  }

  test("prefixed-literals") {
    val obtained = SQLTokenizer.tokenize("r'a' B'0' n'c' X'0F' rb'd' xy'e'")

    val expected = List[SQLToken](
      Literal("r'a'", isClosed = true),
      Whitespace(" "),
      Literal("B'0'", isClosed = true),
      Whitespace(" "),
      Literal("n'c'", isClosed = true),
      Whitespace(" "),
      Literal("X'0F'", isClosed = true),
      Whitespace(" "),
      Literal("rb'd'", isClosed = true),
      Whitespace(" "),
      Identifier("xy"),
      Literal("'e'", isClosed = true),
    )

    assertEquals(obtained, expected)
  }

  test("escape-literals") {
    val obtained = SQLTokenizer.tokenize("E'it\\'s' 'C:\\'")

    val expected = List[SQLToken](
      Literal("E'it\\'s'", isClosed = true, isEscaped = true),
      Whitespace(" "),
      Literal("'C:\\'", isClosed = true),
    )

    assertEquals(obtained, expected)
  }

  test("escape-literal-continues-after-a-splice") {
    val lastToken = Literal("E'it", isClosed = false, isEscaped = true)

    val obtained = SQLTokenizer.tokenize("\\'s' AND", Some(lastToken))

    val expected = List[SQLToken](
      Literal("\\'s'", isClosed = true, isEscaped = true),
      Whitespace(" "),
      Keyword("AND"),
    )

    assertEquals(obtained, expected)
  }

  test("strip-margin-margin-is-not-an-operator") {
    val obtained = SQLTokenizer.tokenize("|SELECT 1\n    |  | 2")

    val expected = List[SQLToken](
      Other("|"),
      Keyword("SELECT"),
      Whitespace(" "),
      Number("1"),
      Whitespace("\n    "),
      Other("|"),
      Whitespace("  "),
      Operator("|"),
      Whitespace(" "),
      Number("2"),
    )

    assertEquals(obtained, expected)
  }

  test("margin-is-only-at-the-start-of-the-interpolation") {
    val obtained =
      SQLTokenizer.tokenize("| 2", Some(Identifier("a")))

    val expected = List[SQLToken](
      Operator("|"),
      Whitespace(" "),
      Number("2"),
    )

    assertEquals(obtained, expected)
  }

  test("keywords-types-and-operators") {
    val obtained = SQLTokenizer.tokenize("EXPORT DATA a ^ b || INT64")

    val expected = List[SQLToken](
      Keyword("EXPORT"),
      Whitespace(" "),
      Keyword("DATA"),
      Whitespace(" "),
      Identifier("a"),
      Whitespace(" "),
      Operator("^"),
      Whitespace(" "),
      Identifier("b"),
      Whitespace(" "),
      Operator("||"),
      Whitespace(" "),
      Keyword("INT64"),
    )

    assertEquals(obtained, expected)
  }

}
