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
      path: String = "A.kt",
      includeInnerClasses: Boolean = false,
  ): KotlinMtags =
    new KotlinMtags(
      Input.VirtualFile(path, code),
      includeMembers,
      includeFuzzyReferences,
      includeInnerClasses = includeInnerClasses,
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
      List("a/C#", "a/AKt#", "a/topLevel().", "a/AKt#topLevel().",
        "a/property.", "a/getProperty().", "a/AKt#property.",
        "a/AKt#getProperty()."),
    )
  }

  private def facadeSymbols(code: String, path: String = "A.kt"): List[String] =
    symbols(
      mtags(code, includeMembers = false, path = path).index(),
      s.SymbolOccurrence.Role.DEFINITION,
    ).filter(_.contains("#"))

  test("facade-overloads-and-setter") {
    assertEquals(
      facadeSymbols(
        """|package a
           |class C
           |typealias T = Int
           |fun f() = 1
           |fun f(x: Int) = x
           |var isOn = true
           |""".stripMargin
      ),
      List("a/C#", "a/T#", "a/AKt#", "a/AKt#f().", "a/AKt#f(+1).",
        "a/AKt#isOn.", "a/AKt#isOn().", "a/AKt#setOn()."),
    )
  }

  test("facade-only-with-callables") {
    assertEquals(facadeSymbols("package a\nclass C\n"), List("a/C#"))
    assertEquals(
      facadeSymbols("package a\nclass C\nfun f() = 1\n"),
      List("a/C#", "a/AKt#", "a/AKt#f()."),
    )
  }

  test("facade-file-name") {
    assertEquals(
      facadeSymbols("fun f() = 1\n", path = "/src/my-utils.kt"),
      List("_empty_/My_utilsKt#", "_empty_/My_utilsKt#f()."),
    )
    assertEquals(
      facadeSymbols("fun f() = 1\n", path = "/src/1.kt"),
      List("_empty_/_1Kt#", "_empty_/_1Kt#f()."),
    )
    // The Kotlin compiler upper-cases `ß` to `SS`.
    assertEquals(
      facadeSymbols("fun f() = 1\n", path = "/src/ßeta.kt"),
      List("_empty_/SSetaKt#", "_empty_/SSetaKt#f()."),
    )
  }

  test("facade-jvm-name") {
    assertEquals(
      facadeSymbols(
        """|@file:JvmMultifileClass
           |@file:JvmName("Utils")
           |package a
           |fun f() = 1
           |""".stripMargin
      ),
      List("a/Utils#", "a/Utils__AKt#", "a/Utils#f().", "a/Utils__AKt#f()."),
    )
  }

  test("facade-jvm-name-qualified-and-named") {
    assertEquals(
      facadeSymbols(
        """|@file:kotlin.jvm.JvmName(name = "Utils")
           |package a
           |fun f() = 1
           |""".stripMargin
      ),
      List("a/Utils#", "a/Utils#f()."),
    )
  }

  test("facade-jvm-name-in-brackets") {
    assertEquals(
      facadeSymbols(
        """|@file:[JvmMultifileClass JvmName("Utils")]
           |package a
           |fun f() = 1
           |""".stripMargin
      ),
      List("a/Utils#", "a/Utils__AKt#", "a/Utils#f().", "a/Utils__AKt#f()."),
    )
  }

  test("facade-const-without-getter") {
    // Java sees `getAnswer()` as the function, the constant is a field.
    assertEquals(
      facadeSymbols("package a\nconst val answer = 42\nfun getAnswer() = 1\n"),
      List("a/AKt#", "a/AKt#answer.", "a/AKt#getAnswer()."),
    )
  }

  test("long-string-of-templates") {
    val code = "val s = \"" + "${0}" * 100000 + "\"\nfun after() = 1\n"
    val doc = mtags(code).index()
    assert(
      symbols(doc, s.SymbolOccurrence.Role.DEFINITION)
        .contains("_empty_/after()."),
      clue(symbols(doc, s.SymbolOccurrence.Role.DEFINITION)),
    )
  }

  test("facade-annotation-text-only") {
    assertEquals(
      facadeSymbols(
        """|@file:Suppress("JvmMultifileClass") // JvmName("Wrong")
           |package a
           |fun f() = 1
           |""".stripMargin
      ),
      List("a/AKt#", "a/AKt#f()."),
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
      List("a/Before#", "a/Before#`<init>`().", "a/AKt#", "a/broken().",
        "a/AKt#broken()."),
    )
  }

  private def definitions(code: String): List[String] =
    symbols(mtags(code).index(), s.SymbolOccurrence.Role.DEFINITION)
      .filterNot(sym => sym.endsWith("/") || sym.contains("/AKt#"))

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

  test("function-type-supertype") {
    assertEquals(
      definitions(
        """|package a
           |interface P<out V> : Base<V>, () -> V {
           |  fun get(): V
           |}
           |class Q : (Int) -> String {
           |  override fun invoke(x: Int): String = ""
           |}
           |fun after() = 1
           |""".stripMargin
      ),
      List("a/P#", "a/P#get().", "a/Q#", "a/Q#invoke().", "a/Q#`<init>`().",
        "a/after()."),
    )
  }

  test("function-type-in-type-arguments") {
    assertEquals(
      definitions(
        """|package a
           |fun handlers(): List<(Int) -> Unit> = emptyList()
           |val callbacks: Map<String, (String) -> Boolean> = emptyMap()
           |class H : Base<(Int) -> Unit>() {
           |  fun inside() = 1
           |}
           |fun after() = 1
           |""".stripMargin
      ),
      List("a/handlers().", "a/callbacks.", "a/getCallbacks().", "a/H#",
        "a/H#inside().", "a/H#`<init>`().", "a/after()."),
    )
  }

  test("annotated-enum-entries") {
    assertEquals(
      definitions(
        """|package a
           |enum class E {
           |  @Deprecated("x") OLD,
           |  NEW;
           |  fun f() = 1
           |}
           |""".stripMargin
      ),
      List("a/E#", "a/E#OLD.", "a/E#NEW.", "a/E#f().", "a/E#`<init>`()."),
    )
  }

  test("unterminated-raw-string-at-end") {
    assertEquals(
      definitions("package a\nfun ok() = 1\nval s = \"\"\"abc\n"),
      List("a/ok().", "a/s.", "a/getS()."),
    )
  }

  test("type-on-next-line-after-colon") {
    assertEquals(
      definitions(
        """|package a
           |class C<T> where T :
           |    Any {
           |  fun member() {}
           |}
           |fun after() = 1
           |""".stripMargin
      ),
      List("a/C#", "a/C#member().", "a/C#`<init>`().", "a/after()."),
    )
    // A property type that the user has yet to type.
    assertEquals(
      definitions(
        """|package a
           |val unfinished:
           |enum class E { RED }
           |""".stripMargin
      ),
      List("a/unfinished.", "a/getUnfinished().", "a/E#", "a/E#RED.",
        "a/E#`<init>`()."),
    )
  }

  test("inner-classes-without-members") {
    val doc = mtags(
      """|package a
         |class Outer(val x: Int) {
         |  fun member() = 1
         |  val prop = 2
         |  class Builder {
         |    fun build() = Outer(1)
         |    interface Step
         |  }
         |  companion object {
         |    fun create() = Outer(1)
         |  }
         |  enum class Mode { ON }
         |}
         |fun top() = 1
         |""".stripMargin,
      includeMembers = false,
      includeInnerClasses = true,
    ).index()
    assertEquals(
      symbols(doc, s.SymbolOccurrence.Role.DEFINITION)
        .filterNot(_.endsWith("/")),
      List("a/Outer#", "a/Outer#Builder#", "a/Outer#Builder#Step#",
        "a/Outer#Companion#", "a/Outer#Mode#", "a/AKt#", "a/top().",
        "a/AKt#top()."),
    )
  }

  test("adjacent-template-identifiers") {
    val all = symbols(
      mtags(
        "package a\nfun join(first: String, last: String) = \"$first$last$$\"\n",
        includeFuzzyReferences = true,
      ).index(),
      s.SymbolOccurrence.Role.REFERENCE,
    )
    assertEquals(all.filter(_.contains("$")), Nil)
    val references = all.filter(r =>
      r.endsWith("().") && (r.startsWith("first") || r.startsWith("last"))
    )
    // The parameters, then the template references.
    assertEquals(references, List("first().", "last().", "first().", "last()."))
    // A string that ends with `$` at the end of the file.
    assertEquals(
      definitions("package a\nval s = \"abc$"),
      List("a/s.", "a/getS()."),
    )
  }
}
