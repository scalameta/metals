package tests.pc

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.pc.WithCompilationUnit
import scala.meta.internal.semanticdb.Diagnostic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.pc.reports.EmptyReportContext

import coursierapi.Dependency
import munit.TestOptions
import org.eclipse.lsp4j.DiagnosticSeverity
import tests.BaseCompletionSuite

class CompilerStaleSymSuite extends BaseCompletionSuite {

  private val auxiliaryTypes =
    List("Any", "AnyRef", "Nothing", "Null", "Singleton")
  private lazy val librarySources = createFetch()
    .withClassifiers(Set("sources").asJava)
    .withDependencies(
      Dependency.of("org.scala-lang", "scala-library", scalaVersion)
    )
    .fetch()
    .asScala
    .find(_.getName == s"scala-library-$scalaVersion-sources.jar")
    .get

  private def librarySource(name: String): String = {
    val jar = new JarFile(librarySources)
    try {
      // Scala 2.12 source jars omit these documentation-only definitions.
      if (jar.getJarEntry(s"scala/$name.scala") == null) {
        val declaration = name match {
          case "Any" => "abstract class Any"
          case "AnyRef" => "trait AnyRef extends Any"
          case "Nothing" => "sealed trait Nothing"
          case "Null" => "sealed trait Null"
          case "Singleton" => "final trait Singleton extends Any"
          case _ => fail(s"missing library source: $name")
        }
        s"package scala; $declaration"
      } else {
        val source = scala.io.Source.fromInputStream(
          jar.getInputStream(jar.getJarEntry(s"scala/$name.scala")),
          "UTF-8"
        )
        try source.mkString
        finally source.close()
      }
    } finally jar.close()
  }

  private val healthy =
    """object Healthy {
      |  def cast(value: _root_.scala.AnyRef): String = value.asInstanceOf[String]
      |  def pick(value: String): String = value
      |  def pick(value: _root_.scala.Any): _root_.scala.Any = value
      |  val selected: String = pick("ok")
      |  val ref: _root_.scala.AnyRef = "ok"
      |  val obj: java.lang.Object = ref
      |  def bottom(value: _root_.scala.Nothing): String = value
      |  def throwing: _root_.scala.Nothing = throw new Exception
      |  def nullable(value: _root_.scala.Null): String = value
      |  val nul: String = null
      |  val stable = new Object
      |  val singleton: stable.type = stable
      |  val upper: _root_.scala.Singleton = singleton
      |  val number: _root_.scala.Int = 1 + 2
      |  val bool: _root_.scala.Boolean = number == 3
      |  class Value(val number: Int) extends AnyVal
      |}""".stripMargin

  for {
    macroAnnotations <- List(false, true)
    if !macroAnnotations || scalaVersion.startsWith("2.13.")
    (name, source, requiresRecovery) <- List(
      ("root", "package scala; abstract class Any", true),
      ("user-any", "package example; class Any", false),
      ("source-error", "object Invalid { val value: MissingType = ??? }", false)
    )
  } test(s"core-type-$name-macro-$macroAnnotations") {
    checkRecovery(
      source,
      macroAnnotations,
      requiresRecovery,
      runDiagnostics = false
    )
  }

  for {
    macros <- List(false, true)
    if !macros || scalaVersion.startsWith("2.13.")
    name <- auxiliaryTypes
    interrupted <- List(false, true)
  } test(
    s"auxiliary-${name.toLowerCase}-macro-$macros-interrupted-$interrupted"
  ) {
    checkRecovery(
      librarySource(name),
      macros,
      requiresRecovery = true,
      interrupted
    )
  }

  for (name <- List("AnyVal", "Int", "Boolean"))
    test(s"library-${name.toLowerCase}") {
      checkRecovery(
        librarySource(name),
        macros = false,
        requiresRecovery = false
      )
    }
  test("user-types") {
    checkRecovery(
      "package example; " + auxiliaryTypes.map("class " + _).mkString("; "),
      macros = false,
      requiresRecovery = false
    )
  }
  test("ordinary-source-error") {
    checkRecovery(
      "class Broken extends Broken",
      macros = false,
      requiresRecovery = false
    )
  }

  private def checkRecovery(
      source: String,
      macros: Boolean,
      requiresRecovery: Boolean,
      interrupted: Boolean = false,
      runDiagnostics: Boolean = true
  ): Unit = {
    val base = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
    val pc = base.copy(
      config =
        if (runDiagnostics)
          PresentationCompilerConfigImpl(emitDiagnostics = true)
        else base.config,
      options = base.options.filterNot(_ == "-Ymacro-annotations") ++
        (if (macros) List("-Ymacro-annotations") else Nil)
    )
    implicit val query: PcQueryContext =
      PcQueryContext(None, () => "")(new EmptyReportContext())

    def document(name: String, text: String): TextDocument =
      TextDocument.parseFrom(
        pc.semanticdbTextDocument(
          CompilerVirtualFileParams(
            tmp.resolve(s"$name.scala").toNIO.toUri,
            text,
            shouldReturnDiagnostics = true
          )
        ).get(30, TimeUnit.SECONDS)
      )
    def currentCompiler(): AnyRef = {
      var entered = 0
      val current = pc.compilerAccess
        .withNonInterruptableCompiler(null: AnyRef, EmptyCancelToken) {
          wrapper =>
            entered += 1
            wrapper.compiler()
        }
        .get(30, TimeUnit.SECONDS)
      assertEquals(entered, 1)
      current
    }
    def checkPermanentSymbols(): Unit = {
      val states = pc.compilerAccess
        .withNonInterruptableCompiler(
          List.empty[(String, Boolean, Boolean)],
          EmptyCancelToken
        ) { wrapper =>
          val g = wrapper.compiler()
          import g._
          List(
            definitions.AnyClass,
            definitions.AnyRefClass,
            definitions.NothingClass,
            definitions.NullClass,
            definitions.SingletonClass
          ).map { sym =>
            (
              sym.name.toString,
              definitions.ScalaPackageClass.info.decls.lookup(sym.name) eq sym,
              sym.sourceFile != null || currentRun.symSource.contains(sym)
            )
          }
        }
        .get(30, TimeUnit.SECONDS)
      assertEquals(states, auxiliaryTypes.map(name => (name, true, false)))
    }
    def checkHealthy(stage: String): Unit = {
      val code = healthy.replace("object Healthy", s"object $stage")
      // A new compiler run is essential: cached alias expansion can mask AnyRef damage.
      if (runDiagnostics) {
        val diagnostics = diagnosticsFor(
          CompilerVirtualFileParams(
            tmp.resolve(s"$stage.scala").toNIO.toUri,
            code,
            shouldReturnDiagnostics = true
          ),
          pc
        )
        assertEquals(
          diagnostics
            .filter(_.getSeverity == DiagnosticSeverity.Error)
            .map(_.getMessage),
          Seq.empty
        )
      }
      val doc = document(
        stage + "Semanticdb",
        code.replace(s"object $stage", s"object ${stage}Semanticdb")
      )
      assertEquals(
        doc.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR),
        Nil
      )
      assert(doc.occurrences.exists(_.symbol == "scala/Any#asInstanceOf()."))
      val invalid = document(
        stage + "Invalid",
        s"""object ${stage}Invalid {
           |  val badNull: Int = null
           |  val badSingleton: Singleton = new Object
           |  val badBottom: Nothing = 1
           |}""".stripMargin
      )
      assertEquals(
        invalid.diagnostics
          .filter(_.severity == Diagnostic.Severity.ERROR)
          .flatMap(_.range.map(_.startLine))
          .toSet,
        Set(1, 2, 3)
      )
      checkPermanentSymbols()
    }
    val path = tmp.resolve("Input.scala").toNIO
    Files.write(path, source.getBytes(UTF_8))
    val params = CompilerVirtualFileParams(path.toUri, source)
    try {
      assertEquals(
        document(
          "Before",
          "object Before { val text = 1.toString }"
        ).diagnostics,
        Nil
      )
      val previous = currentCompiler()
      var attempts = 0
      val result = pc.compilerAccess
        .withInterruptableCompiler("unavailable", EmptyCancelToken) { wrapper =>
          attempts += 1
          val _ = new WithCompilationUnit(wrapper.compiler(), params)
          // Deterministically take the cancellation path after real typing, before the postcheck.
          if (interrupted) throw new CancellationException()
          "typed"
        }
        .get(30, TimeUnit.SECONDS)
      val current = currentCompiler()
      checkHealthy("After")
      assertEquals(
        result,
        if (requiresRecovery || interrupted) "unavailable" else "typed"
      )
      assertEquals(attempts, if (requiresRecovery && !interrupted) 2 else 1)
      if (requiresRecovery) assert(current ne previous)
      else assert(current eq previous)
      pc.didClose(path.toUri)
      checkHealthy("Closed")
    } finally pc.shutdown()
  }

  for {
    name <- List("plain", "form-data", "type")
    kind <- List("object", "class")
  } test(s"display-shortening-preserves-compiler-symbol-$kind-$name") {
    val resultType =
      if (kind == "object") s"Tokens.`$name`.type" else s"Tokens.`$name`"
    val model = s"""|package sample
                    |object Tokens { $kind `$name` { def marker: Int = 42 } }
                    |object Model {
                    |  def token: $resultType = ???
                    |  tok@@
                    |}
                    |""".stripMargin
    assert(getItems(model, "Model.scala").nonEmpty)
    assert(getItems(model, "Model.scala", restart = false).nonEmpty)
    val value =
      if (kind == "object") s"Tokens.`$name`" else s"new Tokens.`$name`"
    val items = getItems(
      s"""|package sample
          |object Caller {
          |  val token = $value
          |  token.mar@@
          |}
          |""".stripMargin,
      "Caller.scala",
      restart = false
    )
    assertNoDiff(items.map(_.getLabel).mkString("\n"), "marker: Int")
  }

  checkRenamedTopelevel(
    "multiple-requests",
    "Metals",
    (contents, max) => contents.replace(s"object Metals", s"object Metals$max"),
    s"""|B example
        |Metals123456789 example
        |""".stripMargin
  )

  checkRenamedTopelevel(
    "multiple-requests-backtick",
    "`M Metals M`",
    (contents, max) => contents.replace(s"Metals M", s"Metals M "),
    s"""|B example
        |`M Metals M         ` example
        |""".stripMargin
  )

  def checkRenamedTopelevel(
      name: TestOptions,
      toRename: String,
      renameFunc: (String, Int) => String,
      expected: String
  ): Unit = test(name) {
    val file1 = "A.scala"
    val file2 = "B.scala"
    val baseFile = s"""|package example
                       |object $toRename {
                       |  val x = 1
                       |  x@@
                       |}""".stripMargin
    def loop(fileContents: String, max: Int = 9): Unit = {
      getItems(
        fileContents,
        file1,
        restart = false
      )

      if (max > 0) {
        loop(
          renameFunc(fileContents, max),
          max - 1
        )
      }

    }

    loop(baseFile)

    val items = getItems(
      s"""|package example
          |object B {
          |  val x = 1
          |  example.@@
          |}""".stripMargin,
      file2,
      restart = false
    )
    assertNoDiff(
      items.map(_.getLabel).mkString("\n"),
      expected
    )

  }

}
