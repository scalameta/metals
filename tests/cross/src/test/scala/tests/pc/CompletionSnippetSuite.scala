package tests.pc

import tests.BaseCompletionSuite

class CompletionSnippetSuite extends BaseCompletionSuite {

  checkSnippet(
    "member",
    """
      |object Main {
      |  List.appl@@
      |}
      |""".stripMargin,
    """|apply($0)
       |unapplySeq($0)
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        // the second apply is from scala/collection/BuildFrom#apply(), introduced in 2.13
        """|apply($0)
           |unapplySeq($0)
           |apply($0)
           |""".stripMargin,
      "3" ->
        """|apply($0)
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "scope",
    """
      |object Main {
      |  printl@@
      |
      |}
      |""".stripMargin,
    """|println()
       |println($0)
       |""".stripMargin,
  )

  checkSnippet(
    "nullary",
    """
      |object Main {
      |  List(1).hea@@
      |}
      |""".stripMargin,
    """|head
       |headOption
       |""".stripMargin,
  )

  checkSnippet(
    "nilary",
    s"""|class Hello{
        |  def now() = 25
        |}
        |object Main {
        |  val h = new Hello()
        |  h.no@@
        |}
        |""".stripMargin,
    """|now()
       |""".stripMargin,
    topLines = Some(1),
  )

  checkSnippet(
    "java-nullary",
    """
      |class Foo {
      |  override def toString = "Foo"
      |}
      |object Main {
      |  new Foo().toStrin@@
      |
      |}
      |""".stripMargin,
    // even if `Foo.toString` is nullary, it overrides `Object.toString()`
    // which is a Java non-nullary method with an empty parameter list.
    """|toString()
       |""".stripMargin,
    compat = Map(
      // it's not easy or efficient to figure out is parent is nullary
      // for Scala 2 it showed correctly even for children method
      "3" ->
        """|toString
           |""".stripMargin
    ),
  )

  checkSnippet(
    // Dotty does not currently support fuzzy completions. Please take a look at
    // https://github.com/lampepfl/dotty-feature-requests/issues/314
    "type-empty"
      .tag(IgnoreScala3),
    """
      |object Main {
      |  type MyType = List[Int]
      |  def list : MT@@
      |}
      |""".stripMargin,
    """|MyType
       |""".stripMargin,
  )

  checkSnippet(
    // Dotty does not currently support fuzzy completions. Please take a look at
    // https://github.com/lampepfl/dotty-feature-requests/issues/314
    "type-new-empty"
      .tag(IgnoreScala3),
    """
      |object Main {
      |  class Gen[T]
      |  type MyType = Gen[Int]
      |  new MT@@
      |}
      |""".stripMargin,
    """|MyType
       |""".stripMargin,
  )

  checkSnippet(
    "type",
    s"""|object Main {
        |  val x: scala.IndexedSe@@
        |}
        |""".stripMargin,
    // It's expected to have two separate results, one for `object IndexedSeq` (which should not
    // expand snipppet) and one for `type IndexedSeq[T]`.
    """|IndexedSeq
       |IndexedSeq[$0]
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|IndexedSeq[$0]
           |""".stripMargin
    ),
  )

  checkSnippet(
    "empty-params-with-implicit",
    s"""|object Main {
        |  def doSomething()(implicit x: Int) = x
        |  val bar = doSomethi@@
        |}
        |""".stripMargin,
    "doSomething($0)",
  )

  checkSnippet(
    // handling this in Scala 3 requires covering CompletionKind.Member in enrichWithSymbols
    // and filtering out the non-member items.
    "type2"
      .tag(IgnoreScala3),
    s"""|object Main {
        |  new scala.IndexedSeq@@
        |}
        |""".stripMargin,
    """|IndexedSeq
       |IndexedSeq[$0] {}
       |""".stripMargin,
  )

  checkSnippet(
    "type3",
    s"""|object Main {
        |  def foo(param: ArrayDeque@@)
        |}
        |""".stripMargin,
    """|ArrayDeque[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|ArrayDeque[$0]
           |ArrayDeque
           |ArrayDequeOps
           |""".stripMargin,
      "3" -> // ArrayDeque upper is for java, the lower for scala
        """|ArrayDeque[$0]
           |ArrayDeque[$0]
           |ArrayDequeOps[$0]
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "type4",
    s"""|object Main {
        |  new SimpleFileVisitor@@
        |}
        |""".stripMargin,
    """|SimpleFileVisitor[$0]
       |""".stripMargin,
  )

  checkSnippet(
    "type5",
    s"""|object Main {
        |  new scala.Iterabl@@
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0] {}
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0] {}
           |IterableOnce[$0] {}
           |""".stripMargin,
      "3" ->
        """|Iterable[$0] {}
           |IterableOnce[$0] {}
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "type6",
    s"""|object Main {
        |  def foo: scala.Iterable@@
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin,
      "3" ->
        """|Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "type7",
    s"""|object Main {
        |  def foo(param: List[scala.Iterable@@])
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin,
      "3" ->
        """|Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "type8",
    s"""|
        |class Base {
        |  class Inner
        |}
        |object Upper extends Base
        |object Main {
        |  def foo(param: Uppe@@)
        |}
        |""".stripMargin,
    """|Upper
       |""".stripMargin,
  )

  checkEditLine(
    "trailing-paren",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@()",
    "trailing()",
  )

  checkEditLine(
    "trailing-brace",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@ { }",
    "trailing { }",
  )

  checkEditLine(
    "trailing-brace1",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@{ }",
    "trailing{ }",
  )

  checkEditLine(
    "trailing-eta",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@ _",
    "trailing _",
  )

  checkEditLine(
    "implicit",
    s"""|object Main {
        |  ___
        |}
        |""".stripMargin,
    "List(1).flatte@@",
    "List(1).flatten",
  )

  checkEditLine(
    // no completions are suggested if we already have full type
    "bug1".tag(IgnoreScala3),
    s"""|object Main {
        |  ___
        |}
        |""".stripMargin,
    "scala.util.Try@@(1)",
    "scala.util.Try(1)",
  )

  checkEditLine(
    "case-class",
    s"""|object Main {
        |  ___
        |}
        |""".stripMargin,
    "scala.util.Tr@@(1)",
    "scala.util.Try(1)",
    filter = str => str.contains("Try"),
  )

  checkSnippet(
    "case-class2",
    s"""|object Main {
        |  scala.util.Tr@@
        |}
        |""".stripMargin,
    """|Try
       |Either
       |control
       |""".stripMargin,
    // additional completion when apply method is present
    compat = Map(
      "3" ->
        """|Try
           |Try($0)
           |""".stripMargin,
      "2.12" ->
        """|Try
           |PropertiesTrait
           |Either
           |control
           |""".stripMargin,
    ),
  )

  checkSnippet(
    "case-class3",
    s"""|object Main {
        |  Try@@
        |}
        |""".stripMargin,
    """|Try
       |Breaks.TryBlock
       |""".stripMargin,
    // additional completion when apply method is present
    compat = Map(
      // Note: the class and trait items in here are invalid. So
      // they are filtered out.
      "3" ->
        """|Try
           |Try($0)
           |TryMethods
           |""".stripMargin
    ),
  )

  checkEditLine(
    "symbol",
    s"""|object Main {
        |  val out = new StringBuilder()
        |  ___
        |}
        |""".stripMargin,
    "out.+@@=('a')",
    "out.++==('a')",
    filter = _.contains("++=(s: String)"),
  )

  checkSnippet(
    "multiple-apply",
    s"""|package example
        |
        |case class Widget(name: String, age: Int)
        |object Widget{
        |  def apply(name: String): Widget = Widget(name, 0)
        |  def apply(age: Int): Widget = Widget("name", age)
        |}
        |object Main {
        |  Wi@@
        |}
        |""".stripMargin,
    "Widget -  example",
    compat = Map(
      "3" ->
        """|Widget -  example
           |Widget($0) - (name: String): Widget
           |Widget($0) - (age: Int): Widget
           |Widget($0) - (name: String, age: Int): Widget
           |""".stripMargin
    ),
    includeDetail = true,
  )

  checkSnippet(
    "no-apply",
    s"""|package example
        |
        |object Widget{}
        |object Main {
        |  Wi@@
        |}
        |""".stripMargin,
    "Widget -  example",
    compat = Map(
      "3" ->
        """|Widget -  example
           |""".stripMargin
    ),
    includeDetail = true,
  )

  // https://github.com/scalameta/metals/issues/4004
  checkEdit(
    "extension-param1".tag(IgnoreScala2),
    s"""|package a
        |object Foo:
        |  extension (s: String)
        |    def bar = 0
        |  val bar = "abc".ba@@
    """.stripMargin,
    s"""|package a
        |object Foo:
        |  extension (s: String)
        |    def bar = 0
        |  val bar = "abc".bar
    """.stripMargin,
  )

  // https://github.com/scalameta/metals/issues/4004
  checkEdit(
    "extension-param2".tag(IgnoreScala2),
    s"""|package a
        |object Foo:
        |  extension (s: String)
        |    def bar() = 0
        |  val bar = "abc".ba@@
    """.stripMargin,
    s"""|package a
        |object Foo:
        |  extension (s: String)
        |    def bar() = 0
        |  val bar = "abc".bar()
    """.stripMargin,
  )

}
