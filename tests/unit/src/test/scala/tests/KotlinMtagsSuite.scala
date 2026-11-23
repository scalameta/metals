package tests

import scala.meta.inputs.Input
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Reporter
import scala.meta.internal.mtags.KotlinMtags
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.internal.semanticdb as s

class KotlinMtagsSuite extends munit.FunSuite {

  private def mtags(
      code: String,
      includeMembers: Boolean = true,
      includeFuzzyReferences: Boolean = false,
  ): KotlinMtags =
    new KotlinMtags(
      Input.VirtualFile("A.kt", code),
      includeMembers,
      includeFuzzyReferences,
    )(FailOnReport)

  /** Fails the test when the scanner reports an exception. */
  private object FailOnReport extends ReportContext {
    override def unsanitized(): Reporter = fail("KotlinMtags reported an error")
    override def incognito(): Reporter = unsanitized()
    override def bloop(): Reporter = unsanitized()
  }

  private def symbols(
      doc: s.TextDocument,
      role: s.SymbolOccurrence.Role,
  ): List[String] =
    doc.occurrences.filter(_.role == role).map(_.symbol).toList

  test("references") {
    val doc = mtags(
      """|package a
         |// fun inLineComment() = hidden1
         |/* nested /* comment */ hidden2 */
         |fun f() = listOf(1).map { "${it + offset}: $label" }
         |val s = "plain text hidden3"
         |""".stripMargin,
      includeFuzzyReferences = true,
    ).index()
    val references =
      symbols(doc, s.SymbolOccurrence.Role.REFERENCE).filter(_.endsWith("()."))
    assertEquals(
      references,
      List("a().", "f().", "listOf().", "map().", "it().", "offset().",
        "label().", "s()."),
    )
  }

  test("overrides") {
    val indexer = mtags(
      """|package a
         |class Impl(x: Int) : Base(x), java.io.Closeable, Comparable<Impl> by other {
         |  class Inner : Runnable
         |}
         |""".stripMargin
    )
    indexer.index()
    assertEquals(
      indexer.overrides(),
      List(
        "a/Impl#" -> List("Base", "Closeable", "Comparable")
          .map(UnresolvedOverriddenSymbol(_)),
        "a/Impl#Inner#" -> List(UnresolvedOverriddenSymbol("Runnable")),
      ),
    )
  }

  test("toplevel-members") {
    val indexer = mtags(
      """|package a
         |typealias Names = List<String>
         |class C {
         |  typealias Nested = Int
         |}
         |""".stripMargin
    )
    indexer.index()
    assertEquals(indexer.toplevelMembers().map(_.symbol), List("a/Names#"))
  }

  test("toplevel-only") {
    val doc = mtags(
      """|package a
         |class C {
         |  fun member() = 1
         |  class Nested
         |}
         |fun topLevel() = 1
         |val property = 2
         |""".stripMargin,
      includeMembers = false,
    ).index()
    assertEquals(
      symbols(doc, s.SymbolOccurrence.Role.DEFINITION),
      List("a/C#", "a/topLevel().", "a/property.", "a/getProperty()."),
    )
  }

  test("broken-code") {
    // An unclosed string and an unclosed body still index what comes before.
    val doc = mtags(
      """|package a
         |class Before
         |fun broken() {
         |  val text = "unclosed
         |  if (x {
         |""".stripMargin
    ).index()
    assertEquals(
      symbols(doc, s.SymbolOccurrence.Role.DEFINITION),
      List("a/Before#", "a/Before#`<init>`().", "a/broken()."),
    )
  }

  private def definitions(code: String): List[String] =
    symbols(mtags(code).index(), s.SymbolOccurrence.Role.DEFINITION)
      .filterNot(sym => sym.endsWith("/"))

  test("generic-type-alias-then-class") {
    assertEquals(
      definitions(
        """|package a
           |typealias Names = List<String>
           |class After
           |""".stripMargin
      ),
      List("a/Names#", "a/After#", "a/After#`<init>`()."),
    )
  }

  test("generic-default-value") {
    assertEquals(
      definitions(
        """|package a
           |class C(val x: Map<String, Int> = emptyMap<String, Int>(), val y: Int) {
           |  fun member() {}
           |}
           |""".stripMargin
      ),
      List("a/C#", "a/C#`<init>`().", "a/C#x.", "a/C#getX().", "a/C#y.",
        "a/C#getY().", "a/C#member()."),
    )
  }

  test("secondary-constructors-only") {
    assertEquals(
      definitions(
        """|package a
           |class C {
           |  constructor(x: Int) {}
           |  constructor(x: String) {}
           |}
           |class D
           |""".stripMargin
      ),
      List("a/C#", "a/C#`<init>`().", "a/C#`<init>`(+1).", "a/D#",
        "a/D#`<init>`()."),
    )
  }

  test("soft-keyword-names") {
    assertEquals(
      definitions(
        """|package a
           |val out = 1
           |fun value() = out
           |""".stripMargin
      ),
      List("a/out.", "a/getOut().", "a/value()."),
    )
  }

  test("template-punctuation") {
    assertEquals(
      definitions(
        """|package a
           |class C<T : @Ann("${1 > 0}") Any> {
           |  fun member() = "${listOf(1).map { it > 0 }}"
           |}
           |""".stripMargin
      ),
      List("a/C#", "a/C#member().", "a/C#`<init>`()."),
    )
  }

  test("comparison-default-value") {
    assertEquals(
      definitions(
        """|package a
           |class C(val x: Boolean = a < b, val y: List<Int>)
           |""".stripMargin
      ),
      List("a/C#", "a/C#`<init>`().", "a/C#x.", "a/C#getX().", "a/C#y.",
        "a/C#getY()."),
    )
  }

  test("line-ends-with-question-or-bang") {
    assertEquals(
      definitions(
        """|package a
           |typealias Maybe = String?
           |class After
           |val forced = maybe!!
           |class Last
           |""".stripMargin
      ),
      List("a/Maybe#", "a/After#", "a/After#`<init>`().", "a/forced.",
        "a/getForced().", "a/Last#", "a/Last#`<init>`()."),
    )
  }

  test("template-identifiers-are-references-only") {
    val code =
      """|package a
         |class C {
         |  val s = "${foo +
         |    constructor()} $init"
         |}
         |""".stripMargin
    assertEquals(
      definitions(code),
      List("a/C#", "a/C#s.", "a/C#getS().", "a/C#`<init>`()."),
    )
    // The constructor is the default one on the class line, not `constructor`
    // from the template on line 3.
    val constructorLines = mtags(code).index().occurrences.collect {
      case occ if occ.symbol.endsWith("`<init>`().") => occ.range.get.startLine
    }
    assertEquals(constructorLines.toList, List(1))
    val references = symbols(
      mtags(code, includeFuzzyReferences = true).index(),
      s.SymbolOccurrence.Role.REFERENCE,
    ).filter(_.endsWith("()."))
    assertEquals(
      references,
      List("a().", "C().", "s().", "foo().", "constructor().", "init()."),
    )
  }

  test("modifiers-of-next-declaration") {
    assertEquals(
      definitions(
        """|package a
           |class Outer private constructor() {
           |  class Marker
           |  companion object {
           |    fun create() = Outer()
           |  }
           |  class Before
           |  enum class Color { RED, BLUE }
           |}
           |""".stripMargin
      ),
      List("a/Outer#", "a/Outer#`<init>`().", "a/Outer#Marker#",
        "a/Outer#Marker#`<init>`().", "a/Outer#Companion#",
        "a/Outer#Companion#create().", "a/Outer#Companion#`<init>`().",
        "a/Outer#Before#", "a/Outer#Before#`<init>`().", "a/Outer#Color#",
        "a/Outer#Color#RED.", "a/Outer#Color#BLUE.",
        "a/Outer#Color#`<init>`()."),
    )
  }

  test("constructor-on-next-line") {
    assertEquals(
      definitions(
        """|package a
           |class C
           |    private constructor(val x: Int) {
           |  fun member() {}
           |}
           |class D
           |    @Inject
           |    constructor(val y: Int)
           |""".stripMargin
      ),
      List("a/C#", "a/C#`<init>`().", "a/C#x.", "a/C#getX().", "a/C#member().",
        "a/D#", "a/D#`<init>`().", "a/D#y.", "a/D#getY()."),
    )
  }

  test("constructor-modifiers-with-comments-and-strings") {
    assertEquals(
      definitions(
        """|package a
           |class C private /* why */ constructor(val x: Int)
           |class D @Deprecated("Use factory(") constructor(val y: Int) {
           |  fun member() {}
           |}
           |""".stripMargin
      ),
      List("a/C#", "a/C#`<init>`().", "a/C#x.", "a/C#getX().", "a/D#",
        "a/D#`<init>`().", "a/D#y.", "a/D#getY().", "a/D#member()."),
    )
  }

  test("nullable-function-type-receiver") {
    assertEquals(
      definitions(
        """|package a
           |fun (() -> Unit)?.invokeIfPresent() {
           |  this?.invoke()
           |}
           |""".stripMargin
      ),
      List("a/invokeIfPresent()."),
    )
  }

  test("multiline-where-clause") {
    assertEquals(
      definitions(
        """|package a
           |class C<T> where T : CharSequence,
           |                 T : Comparable<T> {
           |  fun member() {}
           |}
           |fun <T> f(x: T): Int where T : CharSequence,
           |                          T : Comparable<T> = 1
           |fun after() = 2
           |""".stripMargin
      ),
      List("a/C#", "a/C#member().", "a/C#`<init>`().", "a/f().", "a/after()."),
    )
  }

  test("delegation-at-end-of-body") {
    assertEquals(
      definitions(
        """|package a
           |class Outer {
           |  class Inner(delegate: Runnable) : Runnable by delegate
           |}
           |class After
           |""".stripMargin
      ),
      List("a/Outer#", "a/Outer#Inner#", "a/Outer#Inner#`<init>`().",
        "a/Outer#`<init>`().", "a/After#", "a/After#`<init>`()."),
    )
  }

  test("comparison-in-annotation-of-type-parameter") {
    assertEquals(
      definitions(
        """|package a
           |class C<T : @Ann(1 < 2) Any> {
           |  fun member() {}
           |}
           |class After
           |""".stripMargin
      ).filterNot(_.contains("<init>")),
      List("a/C#", "a/C#member().", "a/After#"),
    )
  }

  test("comment-in-package-name") {
    assertEquals(
      definitions("package a./* comment */b\nclass C\n")
        .filterNot(_.contains("<init>")),
      List("a/b/C#"),
    )
  }

  test("annotated-extension-receiver") {
    assertEquals(
      definitions(
        """|package a
           |fun @receiver:Ann String.foo() {}
           |val @receiver:Ann String.bar: Int get() = 1
           |""".stripMargin
      ),
      List("a/foo().", "a/bar.", "a/getBar()."),
    )
  }
}
