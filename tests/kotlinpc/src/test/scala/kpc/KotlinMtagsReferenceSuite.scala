package kpc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.inputs.Input
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.mtags.KotlinMtags
import scala.meta.internal.semanticdb.TextDocuments

import com.intellij.openapi.util.Disposer
import coursierapi.Dependency
import coursierapi.Fetch
import org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi._

/**
 * Compares the symbols of `KotlinMtags` with the declarations that the Kotlin
 * parser finds in the sources of the Kotlin standard library. The expected
 * symbols follow the rules in the Kotlin section of `architecture.md`.
 */
class KotlinMtagsReferenceSuite extends munit.FunSuite {

  private val stdlibVersion = kotlin.KotlinVersion.CURRENT.toString
  // semanticdb-kotlinc 0.6.0 is built for the Kotlin 2.2 compiler.
  private val pluginVersion = "0.6.0"
  private val compilerVersion = "2.2.0"

  test("stdlib-declarations-match-the-kotlin-parser") {
    val jar = Fetch
      .create()
      .withDependencies(
        Dependency.of("org.jetbrains.kotlin", "kotlin-stdlib", stdlibVersion)
      )
      .withClassifiers(Set("sources").asJava)
      .fetch()
      .asScala
      .find(_.getName.endsWith("-sources.jar"))
      .get
    val configuration = new CompilerConfiguration()
    FrontendConfigurationKeysKt.setExtensionsStorage(
      configuration,
      new CompilerPluginRegistrar.ExtensionStorage(),
    )
    val disposable = Disposer.newDisposable()
    val env = KotlinCoreEnvironment.createForProduction(
      disposable,
      configuration,
      EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    val factory = new KtPsiFactory(env.getProject, false)
    val zip = new ZipFile(jar)
    val differences = mutable.ListBuffer.empty[String]
    var files = 0
    var symbols = 0
    for {
      entry <- zip.entries().asScala
      if entry.getName.endsWith(".kt")
    } {
      val text = new String(
        zip.getInputStream(entry).readAllBytes(),
        StandardCharsets.UTF_8,
      )
      val expected = parserSymbols(factory.createFile(entry.getName, text))
      val obtained = new KotlinMtags(
        Input.VirtualFile(entry.getName, text),
        includeMembers = true,
      )(EmptyReportContext)
        .index()
        .occurrences
        .filter(_.role.isDefinition)
        .map(o => s"${o.range.get.startLine}:${withoutOverload(o.symbol)}")
        .filterNot(_.contains("`<init>`"))
        .toSet
      files += 1
      symbols += expected.size
      for (s <- (expected -- obtained).toList.sorted)
        differences += s"${entry.getName} only in the parser: $s"
      for (s <- (obtained -- expected).toList.sorted)
        differences += s"${entry.getName} only in KotlinMtags: $s"
    }
    zip.close()
    Disposer.dispose(disposable)
    println(s"kotlin-stdlib $stdlibVersion: $files files, $symbols symbols")
    assertEquals(differences.take(50).toList, Nil)
  }

  // The examples of the symbol table in the Kotlin section of architecture.md.
  private val formatSample =
    """|package a
       |
       |fun f() = 1
       |fun f(x: Int) = x
       |object Obj
       |val p = 1
       |val isEmpty: Boolean get() = true
       |var isOn = false
       |val String.shout: String
       |    get() = uppercase()
       |typealias Names = List<String>
       |annotation class A(val value: String)
       |enum class Color(val rgb: Int) { RED(1), GREEN(2) }
       |class C {
       |    fun m() = 1
       |    companion object
       |}
       |""".stripMargin

  test("symbol-format-matches-semanticdb-kotlinc") {
    val dir = Files.createTempDirectory("kotlin-format")
    val source = dir.resolve("Sample.kt")
    Files.write(source, formatSample.getBytes(StandardCharsets.UTF_8))
    def classpath(dependency: String): String = {
      val Array(org, name, version) = dependency.split(":")
      Fetch
        .create()
        .withDependencies(Dependency.of(org, name, version))
        .fetch()
        .asScala
        .mkString(java.io.File.pathSeparator)
    }
    val plugin = classpath(s"com.sourcegraph:semanticdb-kotlinc:$pluginVersion")
    val process = new ProcessBuilder(
      Paths.get(sys.props("java.home"), "bin", "java").toString,
      "-cp",
      classpath(
        s"org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion"
      ),
      "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
      "-no-stdlib",
      "-cp",
      classpath(s"org.jetbrains.kotlin:kotlin-stdlib:$compilerVersion"),
      s"-Xplugin=$plugin",
      "-P",
      s"plugin:semanticdb-kotlinc:sourceroot=$dir",
      "-P",
      s"plugin:semanticdb-kotlinc:targetroot=$dir",
      "-d",
      dir.resolve("classes").toString,
      source.toString,
    ).inheritIO().start()
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
      process.destroyForcibly()
      fail("kotlinc did not finish in 5 minutes")
    }
    assertEquals(process.exitValue(), 0, "kotlinc failed")
    val compiler = TextDocuments
      .parseFrom(
        Files.readAllBytes(
          dir.resolve("META-INF/semanticdb/Sample.kt.semanticdb")
        )
      )
      .documents
      .flatMap(_.occurrences)
      .filter(_.role.isDefinition)
      .map(o => s"${o.range.get.startLine}:${o.symbol}")
      .filter(s => s.contains("/") && !s.matches(".*[(\\[]\\w+[)\\]]$"))
      .filterNot(_.contains("`<init>`"))
      .toSet
    val obtained = new KotlinMtags(
      Input.VirtualFile("Sample.kt", formatSample),
      includeMembers = true,
    )(EmptyReportContext)
      .index()
      .occurrences
      .filter(_.role.isDefinition)
      .map(o => s"${o.range.get.startLine}:${o.symbol}")
      .filterNot(_.contains("`<init>`"))
      .toSet
    val differences =
      (compiler -- obtained).toList.sorted.map("semanticdb-kotlinc " + _) ++
        (obtained -- compiler).toList.sorted.map("KotlinMtags " + _)
    // The differences that the table in architecture.md describes, and the
    // members that the compiler generates for an enum class.
    assertNoDiff(
      differences.mkString("\n"),
      """|semanticdb-kotlinc 11:a/A#getValue().
         |semanticdb-kotlinc 12:a/Color#entries.
         |semanticdb-kotlinc 12:a/Color#valueOf().
         |semanticdb-kotlinc 12:a/Color#values().
         |semanticdb-kotlinc 12:a/`<anonymous object at 264>`#
         |semanticdb-kotlinc 12:a/`<anonymous object at 272>`#
         |semanticdb-kotlinc 12:a/getEntries().
         |semanticdb-kotlinc 6:a/getIsEmpty().
         |semanticdb-kotlinc 7:a/getIsOn().
         |semanticdb-kotlinc 7:a/setIsOn().
         |semanticdb-kotlinc 9:a/getShout().
         |KotlinMtags 11:a/A#value().
         |KotlinMtags 12:a/Color#GREEN.
         |KotlinMtags 12:a/Color#RED.
         |KotlinMtags 6:a/isEmpty().
         |KotlinMtags 7:a/isOn().
         |KotlinMtags 7:a/setOn().
         |KotlinMtags 8:a/getShout().
         |""".stripMargin,
    )
  }

  private def withoutOverload(symbol: String): String =
    symbol.replaceAll("""\(\+\d+\)\.$""", "().")

  /** The symbols of `file` that `KotlinMtags` should give, as `line:symbol`. */
  private def parserSymbols(file: KtFile): Set[String] = {
    val text = file.getText
    val result = Set.newBuilder[String]
    def line(offset: Int): Int = text.substring(0, offset).count(_ == '\n')
    def emit(offset: Int, symbol: String): Unit =
      result += s"${line(offset)}:$symbol"
    def encode(name: String): String =
      if (
        name.nonEmpty && Character.isJavaIdentifierStart(name.head) &&
        name.forall(Character.isJavaIdentifierPart)
      ) name
      else s"`$name`"
    def property(
        offset: Int,
        owner: String,
        name: String,
        isVar: Boolean,
        inAnnotation: Boolean,
    ): Unit = {
      emit(offset, owner + encode(name) + ".")
      // A property `isOn` has the getter `isOn()` and the setter `setOn()`.
      val isBoolean =
        name.length > 2 && name.startsWith("is") && !name.charAt(2).isLower
      val getter =
        if (inAnnotation || isBoolean) name else "get" + name.capitalize
      val setter =
        if (isBoolean) "set" + name.substring(2) else "set" + name.capitalize
      emit(offset, owner + encode(getter) + "().")
      if (isVar)
        emit(offset, owner + encode(setter) + "().")
    }
    def walk(declarations: Seq[KtDeclaration], owner: String): Unit =
      declarations.foreach {
        case d: KtEnumEntry =>
          emit(d.getTextOffset, owner + encode(d.getName) + ".")
        case d: KtClassOrObject =>
          val name = Option(d.getName).orElse(d match {
            case o: KtObjectDeclaration if o.isCompanion => Some("Companion")
            case _ => None
          })
          name.foreach { n =>
            val symbol = owner + encode(n) + "#"
            emit(d.getTextOffset, symbol)
            val inAnnotation = d match {
              case c: KtClass => c.isAnnotation
              case _ => false
            }
            for (p <- d.getPrimaryConstructorParameters.asScala)
              if (p.hasValOrVar)
                property(
                  p.getTextOffset,
                  symbol,
                  p.getName,
                  p.isMutable,
                  inAnnotation,
                )
            walk(d.getDeclarations.asScala.toSeq, symbol)
          }
        case d: KtNamedFunction if d.getName != null =>
          emit(d.getTextOffset, owner + encode(d.getName) + "().")
        case d: KtProperty if d.getName != null =>
          property(d.getTextOffset, owner, d.getName, d.isVar, false)
        case d: KtTypeAlias if d.getName != null =>
          emit(d.getTextOffset, owner + encode(d.getName) + "#")
        case _ =>
      }
    val pkg = file.getPackageFqName.pathSegments().asScala
    val owner =
      if (pkg.isEmpty) "_empty_/"
      else pkg.map(n => encode(n.asString) + "/").mkString
    walk(file.getDeclarations.asScala.toSeq, owner)
    result.result()
  }
}
