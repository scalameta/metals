package tests

import scala.meta.internal.metals.Comment
import scala.meta.internal.metals.Keyword
import scala.meta.internal.metals.Literal
import scala.meta.internal.metals.Number
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

}
