package tests

import scala.meta.internal.docstrings.WikiLink

class WikiLinkSuite extends BaseSuite {

  private def checkSplit(
      name: String,
      content: String,
      target: String,
      title: Option[String],
  ): Unit =
    test(name)(
      assertEquals(WikiLink.splitTargetTitle(content), (target, title))
    )

  private def checkOffset(
      name: String,
      text: String,
      offset: Int,
      expected: Option[String],
  ): Unit =
    test(name)(assertEquals(WikiLink.atOffset(text, offset), expected))

  private def checkSee(
      name: String,
      text: String,
      offset: Int,
      expected: Option[String],
  ): Unit =
    test(name)(assertEquals(WikiLink.seeTagAtOffset(text, offset), expected))

  checkSplit("split-plain", "scala.Foo", "scala.Foo", None)
  checkSplit("split-title", "scala.Foo the foo", "scala.Foo", Some("the foo"))
  // A backticked target keeps an embedded space instead of being split as a title.
  checkSplit("split-backtick-space", "`my type`", "`my type`", None)
  checkSplit(
    "split-backtick-space-title",
    "`my type` the type",
    "`my type`",
    Some("the type"),
  )
  // Whitespace inside a parenthesised signature is part of the target.
  checkSplit("split-paren-space", "foo(a: Int) bar", "foo(a: Int)", Some("bar"))
  checkSplit("split-leading-ws", "  scala.Foo  ", "scala.Foo", None)
  // Whitespace inside a type-argument list `[...]` is part of the target too, not a
  // title boundary (scalameta/metals#3383).
  checkSplit(
    "split-type-args",
    "foo[A, B](a: A) label",
    "foo[A, B](a: A)",
    Some("label"),
  )
  checkSplit("split-type-args-only", "Map[K, V]", "Map[K, V]", None)

  checkOffset("offset-double", "see [[scala.Foo]] now", 12, Some("scala.Foo"))
  // The triple-bracket form the old `[[ ]]` regex truncated to `[scala.Foo`.
  checkOffset("offset-triple", "see [[[scala.Foo]]] now", 12, Some("scala.Foo"))
  checkOffset("offset-backtick-space", "[[`my type`]]", 5, Some("`my type`"))
  checkOffset(
    "offset-with-title",
    "[[scala.Foo the foo]]",
    5,
    Some("scala.Foo"),
  )
  checkOffset("offset-outside", "[[scala.Foo]] tail", 15, None)
  checkOffset("offset-none", "no link here", 3, None)
  // The char right after `]]` belongs to no link (and to the next one if
  // adjacent), not to this link.
  checkOffset("offset-after-close", "[[scala.Foo]] tail", 13, None)
  checkOffset("offset-adjacent-first", "[[a.A]][[b.B]]", 2, Some("a.A"))
  checkOffset("offset-adjacent-second-open", "[[a.A]][[b.B]]", 7, Some("b.B"))
  checkOffset("offset-closing-bracket", "[[a.A]]", 6, Some("a.A"))

  // A `@see` BLOCK tag's reference is clickable from source, like the link hover
  // renders it — but only the first token (the target), only when `@see` starts a
  // comment line, and not for a quoted string (scalameta/metals#3383).
  checkSee(
    "see-block-tag",
    " * @see java.util.List desc",
    10,
    Some("java.util.List"),
  )
  checkSee("see-block-member", " * @see #bar label", 9, Some("#bar"))
  // A `@see` in prose (not at a line start) is not a block tag.
  checkSee("see-mid-line-prose", " * text @see java.util.List", 16, None)
  // A quoted `@see "..."` is plain text per the Javadoc spec, not a symbol.
  checkSee("see-quoted-text", " * @see \"just text\"", 10, None)
  // The description after the target is not part of the clickable link.
  checkSee("see-offset-in-title", " * @see java.util.List desc", 24, None)
  // A bare `@see` whose reference sits on a following continuation line still
  // resolves — the parser appends it to the tag body (scalameta/metals#3383).
  checkSee(
    "see-continuation-line",
    " * @see\n *   java.util.ArrayList\n */",
    16,
    Some("java.util.ArrayList"),
  )
  // A bare `@see` with nothing but the comment close after it is not a link.
  checkSee("see-empty-then-close", " * @see\n */", 9, None)
  // An empty bare `@see` must NOT swallow the following `@see`: the second tag's
  // reference is still clickable (scalameta/metals#3383).
  checkSee(
    "see-empty-then-see",
    " * @see\n * @see java.util.List\n */",
    18,
    Some("java.util.List"),
  )
}
