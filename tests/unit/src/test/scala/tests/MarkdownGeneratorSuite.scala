package tests

import java.util.Locale

import scala.meta.internal.docstrings.ImportFallbacks
import scala.meta.internal.docstrings.MetalsSymbolLink
import scala.meta.internal.docstrings.printers.MarkdownGenerator

class MarkdownGeneratorSuite extends BaseSuite {

  // An uppercase URL scheme (`FILE:`/`MAILTO:`) must be recognised as a URL
  // regardless of the default locale. Under the Turkish locale a naive
  // `"FILE".toLowerCase` yields `"fıle"` (dotless i), which used to be
  // misclassified as a symbol link and wrapped with the resolution sentinel
  // (scalameta/metals#3383).
  test("uppercase-url-scheme-locale-insensitive") {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(new Locale("tr"))
      val rendered =
        MarkdownGenerator.fromDocstring(
          "/** [[FILE:/etc/hosts]] and [[MAILTO:a@b.com]] */",
          Map.empty,
        )
      assert(
        !rendered.contains(MetalsSymbolLink.scheme),
        s"URL scheme was treated as a symbol link: $rendered",
      )
      assert(
        rendered.contains("(FILE:/etc/hosts)"),
        s"expected a plain URL link, got: $rendered",
      )
    } finally Locale.setDefault(previous)
  }

  // A URL is recognised only by a leading `scheme:` (any RFC 3986 scheme,
  // including dotted custom ones), never by a bare `/` — because a `/` also
  // appears in Scala operator members (`BigDecimal./`, `divide_/`) and Javadoc
  // module prefixes (`java.base/...`), which must stay symbols
  // (scalameta/metals#3383).
  test("external-uri-schemes-vs-symbol-links") {
    def render(link: String): String =
      MarkdownGenerator.fromDocstring(s"/** $link */", Map.empty)
    def assertUrl(target: String): Unit = {
      val rendered = render(s"[[$target]]")
      assert(
        !rendered.contains(MetalsSymbolLink.scheme),
        s"external link treated as a symbol: $rendered",
      )
      assert(
        rendered.contains(s"($target)"),
        s"expected a verbatim link to $target, got: $rendered",
      )
    }
    def assertSymbol(target: String): Unit = {
      val rendered = render(s"[[$target]]")
      assert(
        rendered.contains(MetalsSymbolLink.scheme),
        s"symbol link was not marked: $rendered",
      )
    }
    // Schemed URLs (incl. non-allowlisted, slash-bearing, and dotted schemes).
    assertUrl("urn:isbn:0451450523")
    assertUrl("jar:file:///lib.jar!/p/C.class")
    assertUrl("jrt:/java.base/java/lang/String.class")
    assertUrl("news:comp.lang.scala")
    assertUrl("x-doc:custom/Page")
    assertUrl("com.example:page")
    // The marker scheme typed by a user stays an EXTERNAL url — the real marker
    // carries a private-use prefix the user can't write, so it is never hijacked
    // into a goto command (scalameta/metals#3383).
    assertUrl("metals-wiki-link2:foo")
    // Symbol links that contain `/` or `:` but are not URLs — including
    // right-associative operator members whose path ends in `+`/`-` before the
    // `:` (a scheme-legal char), which must still be symbols.
    assertSymbol("BigDecimal./")
    assertSymbol("divide_/")
    assertSymbol("java.base/java.lang.String")
    assertSymbol("scala.collection.immutable.:: cons")
    assertSymbol("List.+:")
    assertSymbol("Seq.++:")
    assertSymbol("List.-:")
    assertSymbol("Vector.:+")
  }

  // `fallbacksForTarget` splits a link into the leading import-qualifiable name,
  // its remaining suffix, and whether the wildcard/implicit scopes apply
  // (`bareScope`): a bare name or a single member access qualifies, an
  // already-qualified package path does not. A value-force `$` is kept as a
  // suffix while an embedded `$` and a backtick-escaped name stay whole
  // (scalameta/metals#3383).
  test("fallbacks-for-target-split") {
    def split(
        target: String,
        isJava: Boolean = false,
    ): (String, String, Boolean) = {
      var captured: (String, String, Boolean) = ("", "", false)
      MetalsSymbolLink.fallbacksForTarget(
        target,
        isJava,
        (name, rest, bare) => {
          captured = (name, rest, bare)
          ImportFallbacks.empty
        },
      )
      captured
    }
    // A bare type qualifies.
    assertEquals(split("Future"), ("Future", "", true))
    // An already-qualified package path does not.
    assertEquals(
      split("scala.concurrent.Future"),
      ("scala", ".concurrent.Future", false),
    )
    // A single member access qualifies (`[[Option.empty]]`-style).
    assertEquals(split("Future.successful"), ("Future", ".successful", true))
    assertEquals(split("util.Helper"), ("util", ".Helper", true))
    // Only TOP-LEVEL dots make a package path, so a single member whose name is a
    // backticked dotted string or whose signature carries dots still qualifies —
    // it is NOT mistaken for `scala.concurrent.Future` (scalameta/metals#3383).
    assertEquals(split("util.`a.b`"), ("util", ".`a.b`", true))
    assertEquals(
      split("util.tool(p.q.R)"),
      ("util", ".tool(p.q.R)", true),
    )
    // In SCALA a value-force `$` and a type-force `!` are kept as the suffix; the
    // bare name still qualifies via the wildcard/implicit scope.
    assertEquals(split("Future$"), ("Future", "$", true))
    assertEquals(split("Future!"), ("Future", "!", true))
    // In JAVA `$` is an identifier char with no value-force, so `{@link Money$}`
    // keeps it (else the explicit import key `Money$` is missed).
    assertEquals(split("Money$", isJava = true), ("Money$", "", true))
    assertEquals(split("Money$", isJava = false), ("Money", "$", true))
    // An embedded `$` stays in the name.
    assertEquals(split("Foo$Bar"), ("Foo$Bar", "", true))
    // A `$` before a member access is kept in the name, not a force suffix.
    assertEquals(split("Foo$#bar"), ("Foo$", "#bar", true))
    // A backtick-escaped name (with a space) is returned whole.
    assertEquals(split("`my type`"), ("`my type`", "", true))
  }

  // `memberLinkOwner` identifies the SIMPLE owner an import could rebind, so the
  // resolver can confine member selection to it. A bare type, an already-qualified
  // path, or a signature yields None (scalameta/metals#3383).
  test("member-link-owner") {
    def owner(target: String, isJava: Boolean = false): Option[String] =
      MetalsSymbolLink.memberLinkOwner(target, isJava)
    // A member selected off a simple bare name.
    assertEquals(owner("Child#fromParent"), Some("Child"))
    assertEquals(owner("Child#fromParent(x)"), Some("Child"))
    assertEquals(owner("Future.successful"), Some("Future"))
    assertEquals(owner("`my type`#m"), Some("`my type`"))
    // In Java the owner keeps a trailing `$` (`Money$#field`).
    assertEquals(owner("Money$#field", isJava = true), Some("Money$"))
    // Only TOP-LEVEL dots qualify a path, so a single member whose name is a
    // backticked dotted string or whose signature carries dots still resolves the
    // bare owner `Foo`, letting a local `Foo` cap it (scalameta/metals#3383).
    assertEquals(owner("Foo.`a.b`"), Some("Foo"))
    assertEquals(owner("Foo.bar(java.lang.String)"), Some("Foo"))
    // A type-argument list is stripped, so a GENERIC owner is still capped exactly
    // as the resolver (which also drops `[Int]`) resolves it (scalameta/metals#3383).
    assertEquals(owner("Widget[Int]#paint"), Some("Widget"))
    assertEquals(owner("Widget[Int].paint"), Some("Widget"))
    // A bare type, an object value-force, or a signature: nothing to confine.
    assertEquals(owner("Future"), None)
    assertEquals(owner("Future$"), None)
    assertEquals(owner("Foo(x)"), None)
    // An already-qualified path — a leading package segment an import can't rebind,
    // whether a member (`a.b.Foo#m`) or a plain multi-segment path
    // (`scala.concurrent.Future`, indistinguishable from an object chain
    // `Foo.bar.baz`, so conservatively left uncapped) — yields None.
    assertEquals(owner("a.b.Foo#m"), None)
    assertEquals(owner("scala.concurrent.Future"), None)
    assertEquals(owner("Foo.bar.baz"), None)
  }

}
