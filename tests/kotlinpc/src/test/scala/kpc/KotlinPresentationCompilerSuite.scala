package kpc

import java.lang.reflect.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters._

import scala.meta.internal.kpc.KotlinPresentationCompiler
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.pc.ParentSymbols
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j.Location
import org.slf4j.Logger

class KotlinPresentationCompilerSuite extends munit.FunSuite {

  private val model =
    """|package demo
       |
       |/** A user. */
       |data class User(val name: String, val age: Int) {
       |  fun isAdult(): Boolean = age >= 18
       |}
       |
       |fun greet(u: User): String = "Hi ${u.name}"
       |""".stripMargin

  private val main =
    """|package demo
       |
       |fun main() {
       |  val u = User("Ann", 3)
       |  val msg = greet(u)
       |  println(msg.uppercase())
       |  val xs = listOf(1, 2, 3).map { it * 2 }
       |  val sb = java.lang.StringBuilder()
       |}
       |""".stripMargin

  private val stdlib: Path =
    Paths.get(
      classOf[kotlin.Unit].getProtectionDomain.getCodeSource.getLocation.toURI
    )

  private val dir: Path = Files.createTempDirectory("kotlinpc")
  private val modelPath = write("demo/Model.kt", model)
  private val mainPath = write("demo/Main.kt", main)

  private def write(rel: String, text: String): Path = {
    val p = dir.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
  }

  // Errors that the compiler logs in place of a failed result.
  private val failures = new ConcurrentLinkedQueue[String]()
  private val logger = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[Logger]),
      (_, method, args) => {
        if (method.getName == "error")
          failures.add(Option(args).toList.flatten.mkString(" "))
        if (method.getReturnType == classOf[Boolean]) java.lang.Boolean.FALSE
        else null
      },
    )
    .asInstanceOf[Logger]

  private lazy val pc: PresentationCompiler =
    KotlinPresentationCompiler()
      .withLogger(logger)
      .newInstance(
        "demo",
        List(stdlib).asJava,
        Nil.asJava,
        () => List(modelPath, mainPath).asJava,
      )

  override def afterEach(context: AfterEach): Unit = {
    val logged = failures.asScala.toList
    failures.clear()
    assertEquals(logged, Nil, "the compiler logged a failure")
  }

  override def afterAll(): Unit = pc.shutdown()

  /** Params of an edit, with diagnostics. */
  private def changed(uri: URI, text: String) =
    CompilerVirtualFileParams(uri, text, shouldReturnDiagnostics = true)

  /** Params for `text` with the caret at `@@`, which is removed. */
  private def at(path: Path, text: String) = {
    val offset = text.indexOf("@@")
    assert(offset >= 0, "missing @@")
    CompilerOffsetParams(path.toUri, text.replace("@@", ""), offset)
  }

  private def caret(text: String, before: String, inserted: String = "@@") =
    text.replace(before, inserted + before)

  test("hover-project-function") {
    val hover = pc.hover(at(mainPath, caret(main, "eet(u)"))).get()
    assertNoDiff(hover.get.signature.get, "fun greet(u: User): String")
  }

  test("hover-stdlib-and-type") {
    val hover = pc.hover(at(mainPath, caret(main, "Of(1, 2, 3)"))).get()
    assertNoDiff(
      hover.get.signature.get,
      "fun <T> listOf(vararg elements: T): List<T>",
    )
  }

  test("hover-declaration") {
    val hover = pc.hover(at(modelPath, caret(model, "sAdult"))).get()
    assertNoDiff(hover.get.signature.get, "fun isAdult(): Boolean")
  }

  test("definition-project") {
    val result = pc.definition(at(mainPath, caret(main, "reet(u)"))).get()
    assertEquals(result.symbol, "demo/greet().")
    val List(location) = result.locations.asScala.toList
    assertEquals(location.getUri, modelPath.toUri.toString)
    assertEquals(location.getRange.getStart.getLine, 7)
  }

  test("definition-stdlib-symbol") {
    val result = pc.definition(at(mainPath, caret(main, "case()"))).get()
    assertEquals(result.symbol, "kotlin/text/uppercase().")
    assertEquals(result.locations.asScala.toList, Nil)
  }

  test("definition-jdk-symbol") {
    val result = pc.definition(at(mainPath, caret(main, "Builder()"))).get()
    assertEquals(result.symbol, "java/lang/StringBuilder#`<init>`().")
  }

  test("type-definition") {
    val result = pc.typeDefinition(at(mainPath, caret(main, "u)\n"))).get()
    assertEquals(result.symbol, "demo/User#")
    val List(location) = result.locations.asScala.toList
    assertEquals(location.getUri, modelPath.toUri.toString)
    assertEquals(location.getRange.getStart.getLine, 3)
  }

  private def completions(text: String): List[String] =
    pc.complete(at(mainPath, text))
      .get()
      .getItems
      .asScala
      .map(_.getLabel)
      .toList

  test("completion-members") {
    val text = main.replace("  val sb", "  u.isA@@\n  val sb")
    assertEquals(completions(text), List("isAdult"))
  }

  test("completion-stdlib-extension") {
    val text = main.replace("  val sb", "  msg.substringB@@\n  val sb")
    assertEquals(
      completions(text),
      List("substringBefore", "substringBeforeLast"),
    )
  }

  test("completion-stdlib-extension-property") {
    val text = main.replace("  val sb", "  msg.lastInd@@\n  val sb")
    assertEquals(
      completions(text),
      List("lastIndex", "lastIndexOf", "lastIndexOfAny"),
    )
  }

  test("completion-stdlib-function") {
    val text = main.replace("  val sb", "  listO@@\n  val sb")
    assertEquals(completions(text), List("listOf", "listOfNotNull"))
  }

  test("completion-stdlib-class") {
    val text = main.replace("  val sb", "  Rege@@\n  val sb")
    assertEquals(completions(text), List("Regex", "RegexOption"))
  }

  test("completion-inside-name") {
    val text = main.replace("msg.uppercase()", "msg.upper@@case()")
    assertEquals(completions(text), List("uppercase"))
  }

  test("completion-implicit-receiver-extension") {
    val text = main.replace("  val sb", "  msg.run { substringB@@ }\n  val sb")
    assertEquals(
      completions(text),
      List("substringBefore", "substringBeforeLast"),
    )
  }

  test("completion-empty-prefix") {
    val text = main.replace("  val sb", "  u.@@\n  val sb")
    val items = completions(text)
    for (name <- List("name", "age", "isAdult", "copy"))
      assert(items.contains(name), clue(items))
  }

  private def importing(name: String) =
    main.replace("package demo\n", s"package demo\nimport $name\n")

  test("completion-import-jdk-class") {
    val text = importing("java.util.ArrayL@@")
    assertEquals(completions(text), List("ArrayList"))
  }

  test("completion-import-stdlib-class") {
    val text = importing("kotlin.text.Rege@@")
    assertEquals(completions(text), List("Regex", "RegexOption"))
  }

  test("completion-import-stdlib-function") {
    val text = importing("kotlin.collections.listO@@")
    assertEquals(completions(text), List("listOf", "listOfNotNull"))
  }

  test("completion-import-subpackage") {
    val text = importing("java.ut@@")
    assertEquals(completions(text), List("util"))
  }

  test("completion-import-source-class") {
    val text = importing("demo.Us@@")
    assertEquals(completions(text), List("User"))
  }

  test("completion-qualified-jdk-class") {
    val text =
      main.replace("  val sb", "  val l = java.util.ArrayL@@\n  val sb")
    assertEquals(completions(text), List("ArrayList"))
  }

  test("completion-escaped-names") {
    val text = main.replace("  val sb", "  wh@@\n  val sb") +
      "fun `when`() {}\nclass `white space`\n"
    val items = pc.complete(at(mainPath, text)).get().getItems.asScala
    assertEquals(
      items.map(i => i.getLabel -> i.getInsertText).toList,
      List("when" -> "`when`", "white space" -> "`white space`"),
    )
  }

  test("completion-inside-backticks") {
    val text = main.replace("  val sb", "  `wh@@`\n  val sb") +
      "fun `when`() {}\n"
    val items = pc.complete(at(mainPath, text)).get().getItems.asScala
    assertEquals(
      items.map(i => i.getLabel -> i.getInsertText).toList,
      List("when" -> "when"),
    )
  }

  test("completion-import-extension") {
    val text = importing("kotlin.text.substringB@@")
    assertEquals(
      completions(text),
      List("substringBefore", "substringBeforeLast"),
    )
  }

  test("completion-qualified-skips-extension") {
    val decls = "fun String.shout() = 1\nfun shoutAll() = 1\n"
    syncWith(main + decls, model)
    val text =
      main.replace("  val sb", "  demo.shou@@\n  val sb") + decls
    try assertEquals(completions(text), List("shoutAll"))
    finally syncWith(main, model)
  }

  test("completion-stdlib-inline-only-extensions") {
    for (name <- List("let", "apply", "also")) {
      val text =
        main.replace("  val sb", s"  msg.${name.init}@@\n  val sb")
      assert(completions(text).contains(name), clue(completions(text)))
    }
  }

  test("completion-stdlib-inline-only-functions") {
    for (name <- List("println", "TODO")) {
      val text = main.replace("  val sb", s"  ${name.init}@@\n  val sb")
      assert(completions(text).contains(name), clue(completions(text)))
    }
  }

  test("completion-stdlib-skips-internal") {
    val text = main.replace("  val sb", "  requir@@\n  val sb")
    assertEquals(completions(text), List("require", "requireNotNull"))
  }

  test("completion-stdlib-skips-internal-class") {
    val items = completions(main.replace("  val sb", "  Array@@\n  val sb"))
    assert(items.contains("ArrayDeque"), clue(items))
    assert(!items.contains("ArrayAsCollection"), clue(items))
  }

  test("completion-import-skips-internal") {
    val items = completions(importing("kotlin.text.re@@"))
    assert(items.contains("replace"), clue(items))
    assert(!items.contains("requireNonNegativeLimit"), clue(items))
    val classes = completions(importing("kotlin.collections.Array@@"))
    assert(classes.contains("ArrayDeque"), clue(classes))
    assert(!classes.contains("ArrayAsCollection"), clue(classes))
  }

  test("completion-scope") {
    val text = main.replace("  val sb", "  gre@@\n  val sb")
    assert(completions(text).contains("greet"), clue(completions(text)))
  }

  private def errors(path: Path, text: String): List[String] =
    pc.didChange(changed(path.toUri, text))
      .get()
      .asScala
      .map(d => s"${d.getCode.getLeft}: ${d.getMessage.getLeft}")
      .toList

  test("diagnostics") {
    val text = main.replace("  val sb", "  val bad: Int = \"oops\"\n  val sb")
    assertEquals(
      errors(mainPath, text),
      List(
        "INITIALIZER_TYPE_MISMATCH: Initializer type mismatch: expected 'Int', actual 'String'."
      ),
    )
  }

  test("did-change-without-diagnostics") {
    val renamed = model.replace("fun greet(", "fun welcome(") +
      "val bad: Int = \"oops\"\n"
    val params = CompilerVirtualFileParams(modelPath.toUri, renamed)
    try {
      assertEquals(pc.didChange(params).get().asScala.toList, Nil)
      // The edit is recorded: Main.kt sees the new name.
      val text = main.replace("  val sb", "  welc@@\n  val sb")
      assertEquals(completions(text), List("welcome"))
    } finally syncSession()
  }

  test("completion-library-constant") {
    val text = importing("kotlin.math.P@@")
    assert(completions(text).contains("PI"), clue(completions(text)))
  }

  test("buffer-edit-in-another-file") {
    // `greet` is renamed in the open Model.kt buffer; Main.kt must see it.
    pc.didChange(
      changed(
        modelPath.toUri,
        model.replace("fun greet(", "fun welcome("),
      )
    ).get()
    assertEquals(
      errors(mainPath, main),
      List("UNRESOLVED_REFERENCE: Unresolved reference 'greet'."),
    )
    pc.didChange(changed(modelPath.toUri, model)).get()
    assertEquals(errors(mainPath, main), Nil)
  }

  test("buffer-changes-apply-in-call-order") {
    // The second call must see the first one, without waiting for it.
    pc.didChange(
      changed(
        modelPath.toUri,
        model.replace("fun greet(", "fun welcome("),
      )
    )
    assertEquals(
      errors(mainPath, main),
      List("UNRESOLVED_REFERENCE: Unresolved reference 'greet'."),
    )
    pc.didChange(changed(modelPath.toUri, model)).get()
  }

  test("close-drops-unsaved-edits") {
    pc.didChange(
      changed(
        modelPath.toUri,
        model.replace("fun greet(", "fun welcome("),
      )
    ).get()
    pc.didClose(modelPath.toUri)
    assertEquals(errors(mainPath, main), Nil)
  }

  test("no-session-after-shutdown") {
    val other = KotlinPresentationCompiler().newInstance(
      "other",
      List(stdlib).asJava,
      Nil.asJava,
      () => List(modelPath, mainPath).asJava,
    )
    val params = at(mainPath, caret(main, "eet(u)"))
    assert(other.hover(params).get().isPresent)
    other.shutdown()
    assert(!other.hover(params).get().isPresent)
  }

  test("completion-local") {
    val text = main.replace("  val sb", "  ms@@\n  val sb")
    assertEquals(completions(text), List("msg"))
  }

  test("type-definition-on-declaration") {
    val result = pc.typeDefinition(at(mainPath, caret(main, "u = User"))).get()
    assertEquals(result.symbol, "demo/User#")
  }

  test("disk-change-of-unopened-file") {
    val other = KotlinPresentationCompiler().newInstance(
      "disk",
      List(stdlib).asJava,
      Nil.asJava,
      () => List(modelPath, mainPath).asJava,
    )
    def diskErrors() = other
      .didChange(changed(mainPath.toUri, main))
      .get()
      .asScala
      .map(_.getCode.getLeft)
      .toList
    assertEquals(diskErrors(), Nil)
    write("demo/Model.kt", model.replace("fun greet(", "fun welcome("))
    try assertEquals(diskErrors(), List("UNRESOLVED_REFERENCE"))
    finally {
      write("demo/Model.kt", model)
      other.shutdown()
    }
  }

  // A session that matches every buffer, so that the next edit of Model.kt
  // is analysed in a copy of the file.
  private def syncSession(): Unit = syncWith(main, model)

  test("body-edit-definition-in-same-file") {
    syncSession()
    val edited = model.replace("\"Hi ${u.name}\"", "\"Hello ${u.name}\"")
    val result =
      pc.definition(at(modelPath, caret(edited, "ame}"))).get()
    val List(location) = result.locations.asScala.toList
    assertEquals(location.getUri, modelPath.toUri.toString)
    assertEquals(location.getRange.getStart.getLine, 3)
    pc.didChange(changed(modelPath.toUri, model)).get()
  }

  test("deleted-declaration-in-same-file") {
    syncSession()
    // The session still has `greet`; only a rebuild drops it.
    val edited = model
      .replace("fun greet(u: User): String = \"Hi ${u.name}\"\n", "")
      .replace("age >= 18", "greet(this).isEmpty()")
    assertEquals(
      errors(modelPath, edited),
      List("UNRESOLVED_REFERENCE: Unresolved reference 'greet'."),
    )
    pc.didChange(changed(modelPath.toUri, model)).get()
  }

  test("rename-with-spaces-in-same-file") {
    syncSession()
    val v1 = model
      .replace("fun greet(", "fun `gr eet`(")
      .replace("age >= 18", "`gr eet`(this).isEmpty()")
    for (name <- List("`greet`", "`gr  eet`")) {
      assertEquals(errors(modelPath, v1), Nil)
      val v2 = v1.replace("fun `gr eet`(", s"fun $name(")
      assertEquals(
        errors(modelPath, v2),
        List("UNRESOLVED_REFERENCE: Unresolved reference 'gr eet'."),
      )
    }
    pc.didChange(changed(modelPath.toUri, model)).get()
  }

  test("definition-through-another-file-after-body-edit") {
    val withMake = main + "fun make() = User(\"A\", 1)\n"
    pc.didChange(changed(mainPath.toUri, withMake)).get()
    val v1 = model.replace("  fun isAdult", "  init {\n  }\n  fun isAdult")
    pc.didChange(changed(modelPath.toUri, v1)).get()
    // A body edit above `isAdult`, and a use of it through Main.kt's `make`.
    val v2 = v1
      .replace("  init {\n  }", "  init {\n\n\n\n  }")
      .replace("\"Hi ${u.name}\"", "\"Hi ${make().isAdult()}\"")
    val result = pc.definition(at(modelPath, caret(v2, "sAdult()}"))).get()
    val List(location) = result.locations.asScala.toList
    assertEquals(location.getRange.getStart.getLine, 9)
    pc.didChange(changed(mainPath.toUri, main)).get()
    syncSession()
  }

  test("declaration-moved-into-comment") {
    syncSession()
    val v1 = (model + "// note\nclass Gone\n")
      .replace("\"Hi ${u.name}\"", "\"Hi ${Gone()}\"")
    assertEquals(errors(modelPath, v1), Nil)
    val v2 = v1.replace("// note\nclass Gone", "// note class Gone")
    assertEquals(
      errors(modelPath, v2),
      List("UNRESOLVED_REFERENCE: Unresolved reference 'Gone'."),
    )
    pc.didChange(changed(modelPath.toUri, model)).get()
  }

  /** Sets Main.kt to `mainText` and Model.kt to `modelText` in one session. */
  private def syncWith(mainText: String, modelText: String): Unit = {
    pc.didChange(changed(mainPath.toUri, mainText)).get()
    pc.didChange(changed(modelPath.toUri, modelText)).get()
  }

  test("sealed-inheritor-in-another-file-after-edit") {
    val v1 = model + "class Child : Base\n"
    syncWith(main + "sealed interface Base\n", v1)
    assertEquals(errors(modelPath, v1 + "\n"), Nil)
    syncWith(main, model)
  }

  test("removed-body-makes-method-abstract") {
    val v1 = model + "interface I { fun f() {} }\nclass C : J\n"
    syncWith(main + "interface J : I\n", v1)
    assertEquals(errors(modelPath, v1), Nil)
    val v2 = v1.replace("fun f() {}", "fun f()")
    assertEquals(
      errors(modelPath, v2).map(_.takeWhile(_ != ':')),
      List("ABSTRACT_MEMBER_NOT_IMPLEMENTED"),
    )
    syncWith(main, model)
  }

  test("internal-in-another-file-after-edit") {
    val v1 = model.replace("\"Hi ${u.name}\"", "\"Hi ${secret()}\"")
    syncWith(main + "internal fun secret() = 1\n", v1)
    assertEquals(errors(modelPath, v1 + "\n"), Nil)
    syncWith(main, model)
  }

  // The classpath definition index finds a class file by the owner of a
  // symbol, so a library top-level function needs its JVM facade class.
  test("definition-library-facade-symbol") {
    val queried = List.newBuilder[String]
    val search = new SymbolSearch {
      def definition(symbol: String, source: URI): util.List[Location] = {
        queried += symbol
        util.Collections.emptyList()
      }
      def documentation(symbol: String, parents: ParentSymbols) =
        EmptySymbolSearch.documentation(symbol, parents)
      def definitionSourceToplevels(symbol: String, source: URI) =
        EmptySymbolSearch.definitionSourceToplevels(symbol, source)
      def search(query: String, target: String, visitor: SymbolSearchVisitor) =
        EmptySymbolSearch.search(query, target, visitor)
      def searchMethods(
          query: String,
          target: String,
          visitor: SymbolSearchVisitor,
      ) = EmptySymbolSearch.searchMethods(query, target, visitor)
    }
    val withSearch = pc.withSearch(search)
    try
      for (place <- List("case()", "Of(1, 2, 3)", "rintln("))
        withSearch.definition(at(mainPath, caret(main, place))).get()
    finally withSearch.shutdown()
    assertEquals(
      queried.result(),
      List(
        "kotlin/text/uppercase().", "kotlin/text/StringsKt#uppercase().",
        "kotlin/collections/listOf().",
        "kotlin/collections/CollectionsKt#listOf().", "kotlin/io/println().",
        "kotlin/io/ConsoleKt#println().",
      ),
    )
  }

  test("completion-hides-private-members") {
    val v1 = model.replace(
      "  fun isAdult()",
      "  private fun isSecret() = true\n  fun isAdult()",
    )
    syncWith(main, v1)
    val text = main.replace("  val sb", "  u.is@@\n  val sb")
    assertEquals(completions(text), List("isAdult"))
    syncWith(main, model)
  }

  test("completion-package-hides-private-declarations") {
    val v1 = model + "private fun shoutSecret() = 1\nfun shoutAll() = 1\n" +
      "internal fun shoutHere() = 1\nprivate class ShoutBox\nclass ShoutList\n" +
      "internal class ShoutLocal\n"
    syncWith(main, v1)
    try {
      val qualified = main.replace("  val sb", "  demo.Shou@@\n  val sb")
      assertEquals(completions(qualified), List("ShoutList", "ShoutLocal"))
      assertEquals(
        completions(importing("demo.shou@@")),
        List("shoutAll", "shoutHere"),
      )
    } finally syncWith(main, model)
  }
}
