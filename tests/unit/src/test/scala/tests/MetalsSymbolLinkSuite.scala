package tests

import scala.meta.internal.docstrings.DocScope
import scala.meta.internal.docstrings.ImportLevel
import scala.meta.internal.docstrings.ImportScope
import scala.meta.internal.docstrings.MetalsSymbolLink

/**
 * Round-trips the structured wiki-link marker (`DocScope` + target) through
 * `withDocScope` (embed) and `parsePayload` (extract), since a codec bug would
 * silently break hover navigation (scalameta/metals#3383).
 */
class MetalsSymbolLinkSuite extends BaseSuite {

  private def roundTrip(docScope: DocScope, target: String): Unit = {
    val rendered =
      s"see [x](${MetalsSymbolLink.scheme}${MetalsSymbolLink.encode(target)}) end"
    val embedded = MetalsSymbolLink.withDocScope(rendered, docScope)
    val start =
      embedded.indexOf(MetalsSymbolLink.scheme) + MetalsSymbolLink.scheme.length
    val end = embedded.indexOf(')', start)
    val parsed = MetalsSymbolLink.parsePayload(embedded.substring(start, end))
    assertEquals(parsed.target, target, s"target for $docScope")
    assertEquals(parsed.docScope, docScope, s"docScope for $target")
  }

  test("empty-scope")(roundTrip(DocScope.empty, "scala.Foo"))

  // The scope payload is injected only at a real marker TARGET (`](`+scheme), never
  // at a `scheme` occurrence inside the visible label, which would corrupt the
  // rendered hover text (scalameta/metals#3383).
  test("scheme-in-label-not-corrupted") {
    val target = "scala.Foo"
    val label = s"see ${MetalsSymbolLink.scheme} here"
    val rendered =
      s"[$label](${MetalsSymbolLink.scheme}${MetalsSymbolLink.encode(target)})"
    val out = MetalsSymbolLink.withDocScope(
      rendered,
      DocScope(
        Some("a/O."),
        None,
        isJava = false,
        ImportScope.empty,
        None,
        docIsScala3 = false,
      ),
    )
    // The label (including its scheme substring) is preserved verbatim.
    assert(out.contains(s"[$label]"), out)
    // The target still round-trips through the (prefixed) marker.
    val payloadStart =
      out.indexOf(MetalsSymbolLink.markerLinkOpen) +
        MetalsSymbolLink.markerLinkOpen.length
    val payloadEnd = out.indexOf(')', payloadStart)
    val parsed =
      MetalsSymbolLink.parsePayload(out.substring(payloadStart, payloadEnd))
    assertEquals(parsed.target, target)
    assertEquals(parsed.docScope.owner, Some("a/O."))
  }

  test("owner-and-alternative")(
    roundTrip(
      DocScope(
        Some("a/O."),
        Some("a/O#"),
        isJava = false,
        ImportScope.empty,
        Some("file:///a/O.scala"),
        docIsScala3 = true,
      ),
      "foo",
    )
  )

  test("explicit-imports")(
    roundTrip(
      DocScope(
        Some("a/O."),
        None,
        isJava = false,
        ImportScope(
          List(
            ImportLevel(
              Map(
                "Future" -> List("scala.concurrent.Future", "a.b.Future"),
                "X" -> List("p.X"),
              ),
              Nil,
            )
          )
        ),
        None,
        docIsScala3 = false,
      ),
      "Future",
    )
  )

  test("wildcards-with-type-force")(
    roundTrip(
      DocScope(
        None,
        None,
        isJava = true,
        ImportScope(
          List(
            ImportLevel(
              Map.empty,
              List(
                ("a.b", Set("X", "Y"), false),
                ("c", Set.empty[String], true),
              ),
            )
          )
        ),
        None,
        docIsScala3 = false,
      ),
      "Foo",
    )
  )

  // Two nested scopes: each level keeps its explicit and wildcard imports together
  // and round-trips in order (innermost first).
  test("multiple-scopes")(
    roundTrip(
      DocScope(
        Some("a/O."),
        None,
        isJava = false,
        ImportScope(
          List(
            ImportLevel(
              Map("A" -> List("p.A")),
              List(("inner", Set.empty[String], false)),
            ),
            ImportLevel(
              Map("B" -> List("q.B")),
              List(("outer", Set("Z"), true)),
            ),
          )
        ),
        Some("file:///pkg/A.scala"),
        docIsScala3 = true,
      ),
      "A.member",
    )
  )

  // Names, targets, prefixes, hidden names and owner symbols may contain spaces,
  // dots, operators, backticks and the structural delimiters (including the `~`
  // level separator); URL-encoding must keep them whole.
  test("special-characters")(
    roundTrip(
      DocScope(
        Some("`a.b`/O."),
        Some("x=y&z|w;v,u~t"),
        isJava = false,
        ImportScope(
          List(
            ImportLevel(
              Map(
                "`my type`" -> List("`pkg name`.`my type`"),
                "===" -> List("cats.syntax.eq.==="),
              ),
              List(("a b", Set("X.Y", "Z|W~V"), true)),
            )
          )
        ),
        Some("file:///a b/x=y;z,w|v~u.scala"),
        docIsScala3 = false,
      ),
      "`my type`",
    )
  )
}
