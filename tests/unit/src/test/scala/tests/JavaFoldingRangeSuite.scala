package tests

import java.nio.file.Paths

import scala.meta.internal.parsing.JavaFoldingRangeExtractor
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind

final class JavaFoldingRangeSuite extends BaseSuite {

  // A block comment with a matching `/*` and `*/` is folded, and the
  // surrounding class and method ranges are detected as usual.
  test("complete-block-comment") {
    check(
      """|class Foo {
         |  /* a block
         |     comment */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 6,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 2,
        endCharacter = 15,
      ),
      region(
        startLine = 3,
        startCharacter = 13,
        endLine = 5,
        endCharacter = 3,
      ),
    )
  }

  // A `/*` that is never closed produces no comment range. Because javac
  // treats everything from the `/*` to EOF as a comment, the method that
  // follows is swallowed and only the enclosing class range remains.
  test("unclosed-block-comment") {
    check(
      """|class Foo {
         |  /* a block
         |     comment that never closes
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 6,
        endCharacter = 2,
      ),
    )
  }

  // A stray `*/` with no matching `/*` is not treated as a comment: while
  // OutsideComment the closing marker is just ordinary text, so no comment
  // range is produced and the class/method ranges are unaffected.
  test("closed-not-opened-block-comment") {
    check(
      """|class Foo {
         |  void bar() {
         |    int x = 1;
         |  }
         |  end of comment */
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      region(
        startLine = 1,
        startCharacter = 13,
        endLine = 3,
        endCharacter = 3,
      ),
    )
  }

  // A `//` inside a block comment is part of the block, not a line comment:
  // the block continues until the real `*/`, yielding a single comment range.
  test("inline-comment-inside-block-comment") {
    check(
      """|class Foo {
         |  /* outer block
         |     // an inline comment here
         |     still block */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 7,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 19,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 6,
        endCharacter = 3,
      ),
    )
  }

  // A `/* ... */` that appears inside a line (`//`) comment must be ignored
  // entirely - no comment range is produced for it.
  test("block-comment-inside-inline-comment") {
    check(
      """|class Foo {
         |  // an inline /* not a block */ comment
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // Regression test: an unterminated `/*` (e.g. inside a line comment)
  // used to send `findComments` into an endless loop.
  test("unterminated-block-comment-in-line-comment") {
    check(
      """|class Foo {
         |  // glob/*.json
         |  void bar() {
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 4,
        endCharacter = 1,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 3,
        endCharacter = 3,
      ),
    )
  }

  // Regression test: a `/*` (or `*/`) that appears inside a line (`//`)
  // comment must not be treated as the start/end of a block comment, so the
  // real block comment that follows is still detected correctly.
  test("block-comment-markers-in-line-comment") {
    check(
      """|class Foo {
         |  // glob/*
         |  /* block
         |     comment */
         |  void bar() {
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 6,
        endCharacter = 1,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 15,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 5,
        endCharacter = 3,
      ),
    )
  }

  // A `//` line that contains a block-comment opener `/*` must not start a
  // block comment: the `/*` is ignored and the complete block comment on the
  // following lines is folded as its own comment range.
  test("block-comment-opener-in-inline-comment-then-block-comment") {
    check(
      """|class Foo {
         |  // see pattern /* here
         |  /* a real
         |     block comment */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 7,
        endCharacter = 1,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 21,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 6,
        endCharacter = 3,
      ),
    )
  }

  // A `/*` or `*/` inside a String literal must be ignored (the literal is an
  // exclusion), so the real block comment that follows is still folded.
  test("block-comment-markers-in-string-literal") {
    check(
      """|class Foo {
         |  String s = "not a /* comment */ here";
         |  /* real
         |     block */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 7,
        endCharacter = 1,
      ),
      region(
        startLine = 1,
        startCharacter = 13,
        endLine = 1,
        endCharacter = 39,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 13,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 6,
        endCharacter = 3,
      ),
    )
  }

  // A `//` inside a String literal (e.g. a URL) must not start a line comment.
  test("line-comment-marker-in-string") {
    check(
      """|class Foo {
         |  String url = "http://example.com";
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      region(
        startLine = 1,
        startCharacter = 15,
        endLine = 1,
        endCharacter = 35,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // A String literal that itself contains both `//` and `/*` markers is
  // excluded, so those markers are ignored and the real block comment that
  // follows is still folded.
  test("string-with-comment-markers-then-block-comment") {
    check(
      """|class Foo {
         |  String s = "a // b /* c";
         |  /* real
         |     block */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 7,
        endCharacter = 1,
      ),
      region(
        startLine = 1,
        startCharacter = 13,
        endLine = 1,
        endCharacter = 26,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 13,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 6,
        endCharacter = 3,
      ),
    )
  }

  // A `"..."` that appears inside a block comment is comment text, not a String
  // literal (javac does not tokenize it), so the whole block comment folds as
  // one range.
  test("string-literal-inside-block-comment") {
    check(
      """|class Foo {
         |  /* comment with "a string" inside */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 1,
        endCharacter = 38,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // A `"..."` inside a line comment is comment text too. Line comments are not
  // folded, so only the class and method ranges remain.
  test("string-literal-inside-line-comment") {
    check(
      """|class Foo {
         |  // comment with "a string" inside
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // A block comment that contains a `"..."` which in turn contains a `//`: it
  // is all comment text, so the whole block comment folds as a single range.
  test("comment-with-string-with-comment") {
    check(
      """|class Foo {
         |  /* outer "inner // still comment" end */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 1,
        endCharacter = 42,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // Two block comments back-to-back with no separator must both be folded.
  test("adjacent-block-comments") {
    check(
      """|class Foo {
         |  int x = 1; /* a *//* b */
         |  void bar() {
         |    int y = 2;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 13,
        endLine = 1,
        endCharacter = 20,
      ),
      comment(
        startLine = 1,
        startCharacter = 20,
        endLine = 1,
        endCharacter = 27,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // A block comment immediately following a String literal (no separator) must
  // still be folded - the literal's exclusive end must not swallow the `/`.
  test("block-comment-right-after-string") {
    check(
      """|class Foo {
         |  String s = "x"/* c */;
         |  void bar() {
         |    int y = 2;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      region(
        startLine = 1,
        startCharacter = 13,
        endLine = 1,
        endCharacter = 16,
      ),
      comment(
        startLine = 1,
        startCharacter = 16,
        endLine = 1,
        endCharacter = 23,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  // Lone `/` division operators must not be read as comment starts, and a real
  // block comment after them must still fold.
  test("division-then-block-comment") {
    check(
      """|class Foo {
         |  int x = 10 / 2 / 5;
         |  /* c */
         |  void bar() {
         |    int y = 2;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 6,
        endCharacter = 1,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 2,
        endCharacter = 9,
      ),
      region(
        startLine = 3,
        startCharacter = 13,
        endLine = 5,
        endCharacter = 3,
      ),
    )
  }

  // Consecutive standalone `//` comments fold as one region.
  test("consecutive-line-comments") {
    check(
      """|class Foo {
         |  // line one
         |  // line two
         |  // line three
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 7,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 15,
      ),
      region(
        startLine = 4,
        startCharacter = 13,
        endLine = 6,
        endCharacter = 3,
      ),
    )
  }

  // A blank line splits consecutive line comments into separate fold regions.
  test("line-comments-separated-by-blank-line") {
    check(
      """|class Foo {
         |  // group a1
         |  // group a2
         |
         |  // group b1
         |  // group b2
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 9,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 2,
        endCharacter = 13,
      ),
      comment(
        startLine = 4,
        startCharacter = 2,
        endLine = 5,
        endCharacter = 13,
      ),
      region(
        startLine = 6,
        startCharacter = 13,
        endLine = 8,
        endCharacter = 3,
      ),
    )
  }

  // A trailing `//` comment after code is not line-leading, so it does not join
  // the standalone comment block that follows.
  test("trailing-line-comment-not-grouped") {
    check(
      """|class Foo {
         |  int x = 1; // trailing
         |  // standalone1
         |  // standalone2
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 4,
        endCharacter = 1,
      ),
      comment(
        startLine = 2,
        startCharacter = 2,
        endLine = 3,
        endCharacter = 16,
      ),
    )
  }

  // A block comment directly above a run of line comments folds as its own
  // region, separate from the line-comment region.
  test("block-and-line-comments-stay-separate") {
    check(
      """|class Foo {
         |  /* a block
         |     comment */
         |  // line one
         |  // line two
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 8,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 2,
        endCharacter = 15,
      ),
      comment(
        startLine = 3,
        startCharacter = 2,
        endLine = 4,
        endCharacter = 13,
      ),
      region(
        startLine = 5,
        startCharacter = 13,
        endLine = 7,
        endCharacter = 3,
      ),
    )
  }

  // A block comment that fits on a single line still folds.
  test("single-line-block-comment") {
    check(
      """|class Foo {
         |  /* one line */
         |  void bar() {
         |    int x = 1;
         |  }
         |}
         |""".stripMargin,
      region(
        startLine = 0,
        startCharacter = 10,
        endLine = 5,
        endCharacter = 1,
      ),
      comment(
        startLine = 1,
        startCharacter = 2,
        endLine = 1,
        endCharacter = 16,
      ),
      region(
        startLine = 2,
        startCharacter = 13,
        endLine = 4,
        endCharacter = 3,
      ),
    )
  }

  private def region(
      startLine: Int,
      startCharacter: Int,
      endLine: Int,
      endCharacter: Int,
  ): FoldingRange =
    fold(
      FoldingRangeKind.Region,
      startLine,
      startCharacter,
      endLine,
      endCharacter,
    )

  private def comment(
      startLine: Int,
      startCharacter: Int,
      endLine: Int,
      endCharacter: Int,
  ): FoldingRange =
    fold(
      FoldingRangeKind.Comment,
      startLine,
      startCharacter,
      endLine,
      endCharacter,
    )

  private def fold(
      kind: String,
      startLine: Int,
      startCharacter: Int,
      endLine: Int,
      endCharacter: Int,
  ): FoldingRange = {
    val range = new FoldingRange(startLine, endLine)
    range.setStartCharacter(startCharacter)
    range.setEndCharacter(endCharacter)
    range.setKind(kind)
    range
  }

  private def textLocationFields(range: FoldingRange) =
    (
      range.getStartLine,
      range.getStartCharacter,
      range.getEndLine,
      range.getEndCharacter,
    )

  private def check(
      text: String,
      expected: FoldingRange*
  ): Unit = {
    val actual = JavaFoldingRangeExtractor
      .extract(
        text,
        path = AbsolutePath(Paths.get("Foo.java")),
        foldOnlyLines = false,
        spanThreshold = 0,
      )
    assertEquals(
      actual.toList.sortBy(textLocationFields),
      expected.toList.sortBy(textLocationFields),
    )
  }
}
