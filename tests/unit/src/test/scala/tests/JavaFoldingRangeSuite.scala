package tests

import java.nio.file.Paths

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.parsing.JavaFoldingRangeExtractor
import scala.meta.io.AbsolutePath

final class JavaFoldingRangeSuite extends BaseSuite {

  // Expected folding ranges are written inline with `>>kind>>` / `<<kind<<`
  // markers (the same convention as the Scala folding suites): `>>region>>`
  // marks a region start, `<<region<<` its end, and likewise for `comment`.

  // ---------------------------------------------------------------------------
  // Line comments
  // ---------------------------------------------------------------------------

  // Consecutive standalone `//` comments fold as one region.
  test("consecutive-line-comments") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// line one
         |  // line two
         |  // line three<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Line comments separated only by blank lines fold together into one region
  // (like imports), spanning from the first comment to the last.
  test("line-comments-separated-by-blank-line") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// group a1
         |  // group a2
         |
         |  // group b1
         |  // group b2<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Code between two runs of line comments keeps them in separate regions: only
  // whitespace-separated comments merge.
  test("line-comments-separated-by-code-stay-separate") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// block a1
         |  // block a2<<comment<<
         |  int x = 1;
         |  >>comment>>// block b1
         |  // block b2<<comment<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A trailing `//` comment after code is not line-leading, so it does not join
  // the standalone comment block that follows.
  test("trailing-line-comment-not-grouped") {
    check(
      """|class Foo >>region>>{
         |  int x = 1; // trailing
         |  >>comment>>// standalone1
         |  // standalone2<<comment<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `/* ... */` that appears inside a line (`//`) comment is not treated as a
  // block comment; the whole line folds as a single line-comment range instead.
  test("block-comment-inside-inline-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// an inline /* not a block */ comment<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Regression test: an unterminated `/*` (e.g. inside a line comment)
  // used to send `findComments` into an endless loop.
  test("unterminated-block-comment-in-line-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// glob/*.json<<comment<<
         |  void bar() >>region>>{
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // ---------------------------------------------------------------------------
  // Block comments
  // ---------------------------------------------------------------------------

  // A block comment with a matching `/*` and `*/` is folded, and the
  // surrounding class and method ranges are detected as usual.
  test("complete-block-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* a block
         |     comment */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A block comment that fits on a single line still folds.
  test("single-line-block-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* one line */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `//` inside a block comment is part of the block, not a line comment:
  // the block continues until the real `*/`, yielding a single comment range.
  test("inline-comment-inside-block-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* outer block
         |     // an inline comment here
         |     still block */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Two block comments back-to-back with no separator must both be folded.
  test("adjacent-block-comments") {
    check(
      """|class Foo >>region>>{
         |  int x = 1; >>comment>>/* a */<<comment<<>>comment>>/* b */<<comment<<
         |  void bar() >>region>>{
         |    int y = 2;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Lone `/` division operators must not be read as comment starts, and a real
  // block comment after them must still fold.
  test("division-then-block-comment") {
    check(
      """|class Foo >>region>>{
         |  int x = 10 / 2 / 5;
         |  >>comment>>/* c */<<comment<<
         |  void bar() >>region>>{
         |    int y = 2;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Regression test: a `/*` (or `*/`) that appears inside a line (`//`)
  // comment must not be treated as the start/end of a block comment, so the
  // real block comment that follows is still detected correctly.
  test("block-comment-markers-in-line-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// glob/*<<comment<<
         |  >>comment>>/* block
         |     comment */<<comment<<
         |  void bar() >>region>>{
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `//` line that contains a block-comment opener `/*` must not start a
  // block comment: the `/*` is ignored and the complete block comment on the
  // following lines is folded as its own comment range.
  test("block-comment-opener-in-inline-comment-then-block-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// see pattern /* here<<comment<<
         |  >>comment>>/* a real
         |     block comment */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `/*` that is never closed produces no comment range. Because javac treats
  // everything from the `/*` to EOF as a comment, the method that follows is
  // swallowed and only the enclosing class range remains.
  test("unclosed-block-comment") {
    check(
      """|class Foo >>region>>{
         |  /* a block
         |     comment that never closes
         |  void bar() {
         |    int x = 1;
         |  }
         |}<<region<<
         |""".stripMargin
    )
  }

  // A stray `*/` with no matching `/*` is not treated as a comment: while
  // OutsideComment the closing marker is just ordinary text, so no comment
  // range is produced and the class/method ranges are unaffected.
  test("closed-not-opened-block-comment") {
    check(
      """|class Foo >>region>>{
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |  end of comment */
         |}<<region<<
         |""".stripMargin
    )
  }

  // A stray `*/` before a method is just ordinary text (no `/*` opened a
  // comment), so it produces no comment range and the following method still
  // folds.
  test("closed-not-opened-block-comment-before-method") {
    check(
      """|class Foo >>region>>{
         |  */
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A block comment directly above a run of line comments folds as its own
  // region, separate from the line-comment region.
  test("block-and-line-comments-stay-separate") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* a block
         |     comment */<<comment<<
         |  >>comment>>// line one
         |  // line two<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // ---------------------------------------------------------------------------
  // Comments and string literals
  // ---------------------------------------------------------------------------

  // A `//` inside a String literal (e.g. a URL) must not start a line comment.
  test("line-comment-marker-in-string") {
    check(
      """|class Foo >>region>>{
         |  String url = >>region>>"http://example.com"<<region<<;
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `/*` or `*/` inside a String literal must be ignored (the literal is an
  // exclusion), so the real block comment that follows is still folded.
  test("block-comment-markers-in-string-literal") {
    check(
      """|class Foo >>region>>{
         |  String s = >>region>>"not a /* comment */ here"<<region<<;
         |  >>comment>>/* real
         |     block */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A String literal that itself contains both `//` and `/*` markers is
  // excluded, so those markers are ignored and the real block comment that
  // follows is still folded.
  test("string-with-comment-markers-then-block-comment") {
    check(
      """|class Foo >>region>>{
         |  String s = >>region>>"a // b /* c"<<region<<;
         |  >>comment>>/* real
         |     block */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `"..."` that appears inside a block comment is comment text, not a String
  // literal (javac does not tokenize it), so the whole block comment folds as
  // one range.
  test("string-literal-inside-block-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* comment with "a string" inside */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A `"..."` inside a line comment is comment text too (not a String literal),
  // so the line folds as a single line-comment range with no string region.
  test("string-literal-inside-line-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>// comment with "a string" inside<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A block comment that contains a `"..."` which in turn contains a `//`: it
  // is all comment text, so the whole block comment folds as a single range.
  test("comment-with-string-with-comment") {
    check(
      """|class Foo >>region>>{
         |  >>comment>>/* outer "inner // still comment" end */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // A block comment immediately following a String literal (no separator) must
  // still be folded - the literal's exclusive end must not swallow the `/`.
  test("block-comment-right-after-string") {
    check(
      """|class Foo >>region>>{
         |  String s = >>region>>"x"<<region<<>>comment>>/* c */<<comment<<;
         |  void bar() >>region>>{
         |    int y = 2;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // ---------------------------------------------------------------------------
  // Imports
  // ---------------------------------------------------------------------------

  // A single import statement folds as its own imports range.
  test("single-import") {
    check(
      """|>>imports>>import java.util.List;<<imports<<
         |class Foo >>region>>{
         |  void bar() >>region>>{}<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Multiple consecutive imports merge into a single imports range spanning from
  // the first import to the last.
  test("consecutive-imports") {
    check(
      """|>>imports>>import java.util.List;
         |import java.util.Map;
         |import java.util.Set;<<imports<<
         |class Foo >>region>>{
         |  void bar() >>region>>{}<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // Imports separated by blank lines still merge into one imports range covering
  // everything from the first import to the last, blank lines included.
  test("imports-separated-by-blank-lines") {
    check(
      """|>>imports>>import java.util.List;
         |
         |import java.util.Map;
         |
         |import java.util.Set;<<imports<<
         |class Foo >>region>>{
         |  void bar() >>region>>{}<<region<<
         |}<<region<<
         |""".stripMargin
    )
  }

  // ---------------------------------------------------------------------------
  // Methods and span thresholds
  // ---------------------------------------------------------------------------

  // A single-line method body spans 0 lines, so at threshold 0 it still folds.
  test("single-line-method-span-threshold-0") {
    check(
      """|class Foo >>region>>{
         |  void bar() >>region>>{}<<region<<
         |}<<region<<
         |""".stripMargin,
      spanThreshold = 0,
    )
  }

  // The same single-line method body is filtered at threshold 1 (span 0 < 1),
  // leaving only the multi-line class region.
  test("single-line-method-span-threshold-1") {
    check(
      """|class Foo >>region>>{
         |  void bar() {}
         |}<<region<<
         |""".stripMargin,
      spanThreshold = 1,
    )
  }

  // With a span threshold above 0, single-line comments (line and block) span 0
  // lines and are filtered out, while the multi-line block comment and the
  // class/method regions still fold.
  test("span-threshold-filters-single-line-comments") {
    check(
      """|class Foo >>region>>{
         |  // single line comment
         |  /* single line block */
         |  >>comment>>/* multi
         |     line block */<<comment<<
         |  void bar() >>region>>{
         |    int x = 1;
         |  }<<region<<
         |}<<region<<
         |""".stripMargin,
      spanThreshold = 1,
    )
  }

  private def check(expected: String, spanThreshold: Int = 0): Unit = {
    val text = expected
      .replace(">>region>>", "")
      .replace("<<region<<", "")
      .replace(">>comment>>", "")
      .replace("<<comment<<", "")
      .replace(">>imports>>", "")
      .replace("<<imports<<", "")
    val actual = JavaFoldingRangeExtractor.extract(
      text,
      path = AbsolutePath(Paths.get("Foo.java")),
      foldOnlyLines = false,
      spanThreshold = spanThreshold,
    )
    val edits = RangesTextEdits.fromFoldingRanges(actual.asJava)
    val obtained = TextEdits.applyEdits(text, edits)
    assertNoDiff(obtained, expected)
  }
}
