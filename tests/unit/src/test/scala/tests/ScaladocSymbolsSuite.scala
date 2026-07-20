package tests

import scala.meta.internal.metals.ContextSymbols
import scala.meta.internal.metals.ScalaDocLink

import munit.TestOptions

class ScaladocSymbolsSuite extends BaseSuite {

  check(
    "class-scaladoc-link",
    "b.O.B!",
    List("a/b/O.B#", "b/O.B#", "a/b/O#B#", "b/O#B#"),
  )

  check(
    "local-scaladoc-link",
    "O.B!",
    List("a/A.O.B#", "a/O.B#", "a/A.O#B#", "a/O#B#"),
  )

  check(
    "object-scaladoc-link",
    "c.b.O.B$",
    List(
      "a/c/b/O.B.", "a/c/b/O.B(+n).", "c/b/O.B.", "c/b/O.B(+n).", "a/c/b/O#B.",
      "a/c/b/O#B(+n).", "c/b/O#B.", "c/b/O#B(+n).",
    ),
  )

  check(
    "method-scaladoc-link",
    "c.b.O.foo(a : Int): String",
    List(
      "a/c/b/O.foo(+n).",
      "c/b/O.foo(+n).",
      "a/c/b/O#foo(+n).",
      "c/b/O#foo(+n).",
    ),
  )

  check(
    "this-scaladoc-link",
    "this.B",
    List("a/A.B#", "a/A.B.", "a/A.B(+n)."),
  )

  check(
    "escape-scaladoc-link",
    "`this.b`.`B.B`",
    List(
      "a/`this.b`/`B.B`#", "a/`this.b`/package.`B.B`#", "a/`this.b`/`B.B`.",
      "a/`this.b`/package.`B.B`.", "a/`this.b`/`B.B`(+n).",
      "a/`this.b`/package.`B.B`(+n).", "`this.b`/`B.B`#",
      "`this.b`/package.`B.B`#", "`this.b`/`B.B`.", "`this.b`/package.`B.B`.",
      "`this.b`/`B.B`(+n).", "`this.b`/package.`B.B`(+n).",
    ),
  )

  check(
    "escape2-scaladoc-link",
    "this\\.B",
    List(
      "a/A.`this.B`#", "a/A.`this.B`.", "a/A.`this.B`(+n).", "a/`this.B`#",
      "a/package.`this.B`#", "a/`this.B`.", "a/package.`this.B`.",
      "a/`this.B`(+n).", "a/package.`this.B`(+n).",
    ),
  )

  // `Type#member` keeps the `#` as a type/member separator instead of being
  // backtick-wrapped as one identifier (scalameta/metals#3383).
  check(
    "qualified-member-scaladoc-link",
    "b.O#foo",
    List(
      "a/b/O#foo#", "a/b/O#foo.", "a/b/O#foo(+n).", "b/O#foo#", "b/O#foo.",
      "b/O#foo(+n).",
    ),
  )

  check(
    "qualified-method-scaladoc-link",
    "b.O#foo(i: Int)",
    List("a/b/O#foo(+n).", "b/O#foo(+n)."),
  )

  // A Javadoc local-member link `#foo` (`{@link #foo}`) resolves against the
  // enclosing class context (scalameta/metals#3383).
  check(
    "local-member-scaladoc-link",
    "#foo",
    List("a/A.foo#", "a/A.foo.", "a/A.foo(+n)."),
  )

  // A trailing operator like `##` is part of the member name, not a `#`
  // separator, so it must not be split (scalameta/metals#3383).
  check(
    "operator-member-scaladoc-link",
    "scala.Any.##",
    List(
      "a/scala/Any.`##`#", "a/scala/Any.`##`.", "a/scala/Any.`##`(+n).",
      "scala/Any.`##`#", "scala/Any.`##`.", "scala/Any.`##`(+n).",
      "a/scala/Any#`##`#", "a/scala/Any#`##`.", "a/scala/Any#`##`(+n).",
      "scala/Any#`##`#", "scala/Any#`##`.", "scala/Any#`##`(+n).",
    ),
  )

  // A Javadoc constructor link `Foo#Foo(...)` names the constructor after the
  // class, but SemanticDB stores it as `<init>` (scalameta/metals#3383).
  check(
    "constructor-scaladoc-link",
    "b.Foo#Foo(i: Int)",
    List("a/b/Foo#`<init>`(+n).", "b/Foo#`<init>`(+n)."),
  )

  // The type of a qualified member link may be a nested type (`Outer#Inner`,
  // e.g. a nested Java class), so both descriptor forms are tried for the inner
  // boundary (scalameta/metals#3383).
  check(
    "nested-type-member-scaladoc-link",
    "Outer.Inner#method()",
    List(
      "a/A.Outer.Inner#method(+n).",
      "a/Outer.Inner#method(+n).",
      "a/A.Outer#Inner#method(+n).",
      "a/Outer#Inner#method(+n).",
    ),
  )

  // A Java module prefix (`module/...`) is not part of the SemanticDB symbol, so
  // it is dropped (scalameta/metals#3383).
  check(
    "module-qualified-scaladoc-link",
    "java.base/java.lang.String#chars()",
    List("a/java/lang/String#chars(+n).", "java/lang/String#chars(+n)."),
  )

  // A Javadoc documentation fragment (`Type##fragment`) links to the type, not a
  // member, so the fragment is dropped (scalameta/metals#3383).
  check(
    "doc-fragment-scaladoc-link",
    "Foo##fragment",
    List(
      "a/A.Foo#", "a/A.Foo.", "a/A.Foo(+n).", "a/Foo#", "a/package.Foo#",
      "a/Foo.", "a/package.Foo.", "a/Foo(+n).", "a/package.Foo(+n).",
    ),
  )

  // A `#` inside a backtick-escaped Scala identifier is part of the name, not a
  // type/member separator, so it must not be split (scalameta/metals#3383).
  check(
    "backtick-hash-scaladoc-link",
    "`Foo#Bar`",
    List(
      "a/A.`Foo#Bar`#", "a/A.`Foo#Bar`.", "a/A.`Foo#Bar`(+n).", "a/`Foo#Bar`#",
      "a/package.`Foo#Bar`#", "a/`Foo#Bar`.", "a/package.`Foo#Bar`.",
      "a/`Foo#Bar`(+n).", "a/package.`Foo#Bar`(+n).",
    ),
  )

  // A backtick-escaped operator identifier may itself contain `(` or `[` (the
  // official Scaladoc abusive example `` `([.abusive.])` ``); those are part of
  // the name, not a method-signature delimiter, so the link is not truncated
  // into a method reference (scalameta/metals#3383).
  check(
    "backtick-abusive-operator-scaladoc-link",
    "`([.abusive.])`",
    List(
      "a/A.`([.abusive.])`#", "a/A.`([.abusive.])`.",
      "a/A.`([.abusive.])`(+n).", "a/`([.abusive.])`#",
      "a/package.`([.abusive.])`#", "a/`([.abusive.])`.",
      "a/package.`([.abusive.])`.", "a/`([.abusive.])`(+n).",
      "a/package.`([.abusive.])`(+n).",
    ),
  )

  // A leading-`#` link whose member is the enclosing class name is a same-class
  // constructor reference, so it also tries the `<init>` form
  // (scalameta/metals#3383).
  check(
    "same-class-constructor-scaladoc-link",
    "#Foo(i: Int)",
    List("a/Foo#`<init>`(+n).", "a/Foo#Foo(+n)."),
    contextSymbols = ContextSymbols("a/", "Foo#", None),
  )

  // A bare `Foo(int)` (no `#`) is treated as a method reference, not a
  // constructor. The constructor form isn't tried, so such a link to a
  // constructor fails safely (resolving to nothing) rather than navigating
  // incorrectly — see scalameta/metals#3383 (known limitation).
  check(
    "bare-constructor-scaladoc-link",
    "Foo(i: Int)",
    List("a/A.Foo(+n).", "a/Foo(+n).", "a/package.Foo(+n)."),
  )

  // A Java member named after a Scala keyword (`Thread#yield`) is stored
  // unwrapped in Java SemanticDB, so the raw form is tried alongside the
  // backtick-wrapped one (scalameta/metals#3383).
  check(
    "java-keyword-member-scaladoc-link",
    "java.lang.Thread#yield()",
    List(
      "a/java/lang/Thread#`yield`(+n).",
      "java/lang/Thread#`yield`(+n).",
      "a/java/lang/Thread#yield(+n).",
      "java/lang/Thread#yield(+n).",
    ),
  )

  // A bare link to a nested type (`{@link Outer.Inner}`, no member) also tries
  // the nested-type form `Outer#Inner` (scalameta/metals#3383).
  check(
    "nested-type-scaladoc-link",
    "Outer.Inner",
    List(
      "a/A.Outer.Inner#", "a/A.Outer.Inner.", "a/A.Outer.Inner(+n).",
      "a/Outer.Inner#", "a/Outer.Inner.", "a/Outer.Inner(+n).",
      "a/A.Outer#Inner#", "a/A.Outer#Inner.", "a/A.Outer#Inner(+n).",
      "a/Outer#Inner#", "a/Outer#Inner.", "a/Outer#Inner(+n).",
    ),
  )

  // A Scala operator member like `/` must not be mistaken for a Java module
  // separator, so the leading `BigDecimal.`/`BigDecimal#` is not stripped
  // (scalameta/metals#3383).
  check(
    "operator-slash-member-scaladoc-link",
    "BigDecimal./",
    List(
      "a/A.BigDecimal.`/`#", "a/A.BigDecimal.`/`.", "a/A.BigDecimal.`/`(+n).",
      "a/BigDecimal.`/`#", "a/BigDecimal.`/`.", "a/BigDecimal.`/`(+n).",
      "a/A.BigDecimal#`/`#", "a/A.BigDecimal#`/`.", "a/A.BigDecimal#`/`(+n).",
      "a/BigDecimal#`/`#", "a/BigDecimal#`/`.", "a/BigDecimal#`/`(+n).",
    ),
  )

  // A same-class constructor reference inside a nested class: the member equals
  // the innermost class name (`Inner`), so the `<init>` form is also tried
  // (scalameta/metals#3383).
  check(
    "nested-same-class-constructor-scaladoc-link",
    "#Inner(i: Int)",
    List("a/Outer#Inner#`<init>`(+n).", "a/Outer#Inner#Inner(+n)."),
    contextSymbols = ContextSymbols("a/", "Outer#Inner#", None),
  )

  // A Java package/type segment named after a Scala keyword (`type.Foo`) is
  // stored unwrapped in Java SemanticDB, so the raw form is tried alongside the
  // backtick-wrapped one (scalameta/metals#3383).
  check(
    "keyword-package-scaladoc-link",
    "type.Foo!",
    List(
      "a/`type`/Foo#", "a/`type`/package.Foo#", "`type`/Foo#",
      "`type`/package.Foo#", "a/type/Foo#", "a/type/package.Foo#", "type/Foo#",
      "type/package.Foo#",
    ),
  )

  // Every object-member (`.`) vs nested-type (`#`) assignment of the type-level
  // boundaries is tried, so a mixed ownership chain such as `Outer.Middle#Inner`
  // (an object holding a nested type) resolves, not only the all-dot and all-`#`
  // extremes (scalameta/metals#3383).
  check(
    "mixed-ownership-chain-scaladoc-link",
    "Outer.Middle.Inner!",
    List(
      "a/A.Outer.Middle.Inner#", "a/Outer.Middle.Inner#",
      "a/A.Outer#Middle.Inner#", "a/Outer#Middle.Inner#",
      "a/A.Outer.Middle#Inner#", "a/Outer.Middle#Inner#",
      "a/A.Outer#Middle#Inner#", "a/Outer#Middle#Inner#",
    ),
  )

  // A `/` that follows `_` is part of a Scala mixed operator identifier
  // (`divide_/`), not a Java module separator, so it must not be stripped
  // (scalameta/metals#3383).
  check(
    "mixed-operator-identifier-scaladoc-link",
    "divide_/(i: Int)",
    List(
      "a/A.`divide_/`(+n).",
      "a/`divide_/`(+n).",
      "a/package.`divide_/`(+n).",
    ),
  )

  // `Type###member` is a member separator `#` followed by the operator member
  // `##`, not a documentation fragment, so it resolves to the member rather than
  // the owner type. A `Type#member` link's type part is a type, not a package
  // object, so no `scala/package.Any` form is generated (scalameta/metals#3383).
  check(
    "operator-after-separator-scaladoc-link",
    "scala.Any###()",
    List(
      "a/scala/Any#`##`(+n).",
      "scala/Any#`##`(+n).",
    ),
  )

  // `List`, `Seq`, `Nil` etc. live in the `scala` package object, so a `scala/X`
  // link also tries the `scala/package.X` symbol that `guessFromPath` can't
  // produce from dots (scalameta/metals#3383).
  check(
    "scala-package-object-scaladoc-link",
    "scala.List!",
    List(
      "a/scala/List#",
      "a/scala/package.List#",
      "scala/List#",
      "scala/package.List#",
    ),
  )

  // A backslash-escaped `\!` is a literal member named `!`, not a force-type
  // suffix, so the character is kept and resolved as a member
  // (scalameta/metals#3383).
  check(
    "escaped-bang-member-scaladoc-link",
    "A.\\!",
    List(
      "a/A.A.`!`#", "a/A.A.`!`.", "a/A.A.`!`(+n).", "a/A.`!`#", "a/A.`!`.",
      "a/A.`!`(+n).", "a/A.A#`!`#", "a/A.A#`!`.", "a/A.A#`!`(+n).", "a/A#`!`#",
      "a/A#`!`.", "a/A#`!`(+n).",
    ),
  )

  // A Java module name ending in `_` (a legal Java identifier char) is still a
  // module prefix and is stripped, since the `/` is followed by a package path
  // (scalameta/metals#3383).
  check(
    "module-underscore-scaladoc-link",
    "foo_/java.lang.String#chars()",
    List("a/java/lang/String#chars(+n).", "java/lang/String#chars(+n)."),
  )

  // A `[...]` is a type-argument list, not a value-parameter signature, so it is
  // stripped and the link resolves against its BASE type (`Map[K, V]` → `Map`,
  // `List[Int]#head` → `List#head`) — never a `Map` method
  // (scalameta/metals#3383).
  test("type-application-scaladoc-link") {
    val ctx = ContextSymbols("a/", "A.", None)
    def symbols(link: String): List[String] =
      ScalaDocLink(link, isScala3 = true)
        .toScalaMetaSymbols(ctx)
        .map(_.showSymbol)
    assertEquals(symbols("Map[K, V]"), symbols("Map"))
    assert(
      symbols("Map[K, V]").exists(_.endsWith("Map#")),
      symbols("Map[K, V]").toString,
    )
    assertEquals(symbols("List[Int]#head"), symbols("List#head"))
    // A generic method with a value signature is still a method of the base name.
    assertEquals(symbols("foo[T](a: Int)"), symbols("foo(a: Int)"))
  }

  def check(
      name: TestOptions,
      symbol: String,
      expected: List[String],
      contextSymbols: ContextSymbols = ContextSymbols("a/", "A.", None),
  ): Unit =
    test(name) {
      assertEquals(
        ScalaDocLink(symbol, isScala3 = true)
          .toScalaMetaSymbols(contextSymbols)
          .map(_.showSymbol),
        expected,
      )
    }

}
