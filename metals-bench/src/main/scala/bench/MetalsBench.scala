package bench

import java.net.URI

import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.io.VirtualFile
import scala.tools.nsc.interactive.Global

import scala.meta.dialects
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.IdentifierIndex
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.LoggerReportContext
import scala.meta.internal.metals.MetalsEnrichments.XtensionScanner
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.mtags.JavaToplevelMtags
import scala.meta.internal.mtags.JavacMtags
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.QdoxJavaMtags
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.mtags.ScalaToplevelMtags
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tests.InputProperties
import tests.Library

@State(Scope.Benchmark)
class MetalsBench {

  MetalsLogger.updateDefaultFormat()
  lazy val inputs: InputProperties = InputProperties.scala2()
  lazy val classpath =
    new SemanticdbClasspath(
      inputs.sourceroot,
      inputs.classpath,
      inputs.semanticdbTargets,
    )
  lazy val documents: List[(AbsolutePath, TextDocument)] =
    inputs.scalaFiles.map { input =>
      (input.file, classpath.textDocument(input.file).get)
    }

  lazy val scalaLibrarySourcesJar: AbsolutePath =
    inputs.dependencySources.entries
      .find(_.toNIO.getFileName.toString.contains("scala-library"))
      .getOrElse {
        sys.error(
          s"missing: scala-library-sources.jar, obtained ${inputs.dependencySources}"
        )
      }

  lazy val jdk: Classpath = Classpath(JdkSources().toOption.get)
  lazy val fullClasspath: Classpath = jdk ++ inputs.dependencySources

  lazy val inflated: Inflated = Inflated.jars(fullClasspath)

  lazy val scalaDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".scala"))
    scribe.info(s"Scala lines: ${result.linesOfCode}")
    result
  }

  lazy val javaLargeDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".java"))
    scribe.info(s"Java lines: ${result.linesOfCode}")
    result
  }

  lazy val javaSmallDependencySources: Inflated =
    javaLargeDependencySources.take(1_000)
  lazy val javaDependencySources: Inflated = {
    javaLargeDependencySources
  }

  lazy val megaSources: Classpath = Classpath(
    Library.allScala2
      .flatMap(_.sources.entries)
      .filter(_.toNIO.getFileName.toString.endsWith(".jar"))
  )

  @Setup
  def setup(): Unit = {
    Flamegraphs.setup()
  }

  @TearDown
  def tearDown(): Unit = {
    Flamegraphs.tearDown()
  }
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsScalaIndex(): Unit = {
    scalaDependencySources.foreach { input =>
      ScalaMtags.index(input, dialects.Scala213).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def toplevelsScalaIndex(): Unit = {
    scalaDependencySources.inputs.foreach { case (input, _) =>
      implicit val rc: ReportContext = LoggerReportContext
      new ScalaToplevelMtags(
        input,
        includeInnerClasses = false,
        includeMembers = false,
        dialects.Scala213,
      ).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def typeHierarchyIndex(): Unit = {
    scalaDependencySources.inputs.foreach { case (input, _) =>
      implicit val rc: ReportContext = LoggerReportContext
      new ScalaToplevelMtags(
        input,
        includeInnerClasses = true,
        includeMembers = false,
        dialects.Scala213,
      ).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalaTokenize(): Unit = {
    scalaDependencySources.foreach { input =>
      val scanner = new LegacyScanner(input, Trees.defaultTokenizerDialect)
      var i = 0
      scanner.foreach(_ => i += 1)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalacTokenize(): Unit = {
    val g = global
    scalaDependencySources.foreach { input =>
      val unit = new g.CompilationUnit(
        new BatchSourceFile(new VirtualFile(input.path), input.chars)
      )
      val scanner = g.newUnitScanner(unit)
      scanner.init()
      while (scanner.token != LegacyToken.EOF) {
        scanner.nextToken()
      }
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalametaParse(): Unit = {
    scalaDependencySources.foreach { input =>
      import scala.meta._
      Trees.defaultTokenizerDialect(input).parse[Source].get
    }
  }

  lazy val global: Global = InteractiveSemanticdb.newCompiler()

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalacParse(): Unit = {
    val g = global
    scalaDependencySources.foreach { input =>
      val unit = new g.CompilationUnit(
        new BatchSourceFile(new VirtualFile(input.path), input.chars)
      )
      val tree = g.newUnitParser(unit).parse()
      var i = 0
      new g.Traverser {
        override def apply[T <: g.Tree](tree: T): T = {
          i += 1
          super.apply(tree)
        }
      }.traverse(tree)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsJavaQdoxParse(): Unit = {
    javaDependencySources.foreach { input =>
      new QdoxJavaMtags(input, includeMembers = true)(EmptyReportContext)
        .index()
    }
  }

  def mtagsJavacParse(): Unit = {
    javaDependencySources.foreach { input =>
      new JavacMtags(input, includeMembers = true)(EmptyReportContext).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsJavacBaseline(): Int = {
    var count = 0
    javaDependencySources.foreach { input =>
      val ctx = new Context()
      val task = JavacMtags.createParserFactory(ctx)
      val source = SourceJavaFileObject.make(input.text, URI.create(input.path))
      Log.instance(ctx).useSource(source)
      val parser = task.factory.newParser(input.text, false, true, true)
      val cu = parser.parseCompilationUnit()
      val visitor = new TreePathScanner[TreePath, Unit]() {
        override def scan(tree: TreePath, p: Unit): TreePath = {
          count += 1
          super.scan(tree, p)
        }
      }
      count += 1
      visitor.scan(cu, ())
      task.fileManager.close()
    }
    count
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsJavacToplevelParse(): Unit = {
    javaDependencySources.foreach { input =>
      new JavacMtags(input, includeMembers = false)(EmptyReportContext).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsJavacFuzzyReferencesParse(): Unit = {
    javaDependencySources.foreach { input =>
      new JavacMtags(
        input,
        includeMembers = true,
        includeFuzzyReferences = true,
        includeUniqueFuzzyReferences = true,
      )(EmptyReportContext).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsJavaToplevelParse(): Unit = {
    javaDependencySources.inputs.foreach { case (input, _) =>
      new JavaToplevelMtags(input, includeInnerClasses = true)(
        LoggerReportContext
      ).index()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def indexSources(): Unit = {
    val index = OnDemandSymbolIndex.empty(mtags = () => Mtags.testingSingleton)
    fullClasspath.entries.foreach(entry =>
      index.addSourceJar(entry, dialects.Scala213)
    )
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def alltoplevelsScalaIndex(): Unit = {
    scalaDependencySources.foreach { input =>
      Mtags.testingSingleton.allToplevels(input, dialects.Scala213)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def alltoplevelsScalaIndexWithCollectIdents(): Unit = {
    scalaDependencySources.foreach { input =>
      new ScalaToplevelMtags(
        input,
        includeInnerClasses = true,
        includeMembers = true,
        dialects.Scala213,
        collectIdentifiers = true,
      )(EmptyReportContext).indexRoot()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def alltoplevelsScalaIndexWithBuildIdentifierIndex(): Unit = {
    val buildTargetIdent = List(
      new BuildTargetIdentifier("id1"),
      new BuildTargetIdentifier("id2"),
      new BuildTargetIdentifier("id3"),
    )
    var btIndex = 0
    val index = new IdentifierIndex
    scalaDependencySources.inputs.foreach { case (input, path) =>
      val indexer = new ScalaToplevelMtags(
        input,
        includeInnerClasses = true,
        includeMembers = true,
        dialects.Scala213,
        collectIdentifiers = true,
      )(EmptyReportContext)

      indexer.indexRoot()

      val identifiers = indexer.allIdentifiers
      if (identifiers.nonEmpty)
        index.addIdentifiers(
          path,
          buildTargetIdent(btIndex),
          identifiers,
        )
      btIndex = (btIndex + 1) % 3
    }
  }

}
