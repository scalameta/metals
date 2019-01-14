package bench

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.io.VirtualFile
import tests.InputProperties
import tests.Libraries

@State(Scope.Benchmark)
class MetalsBench {

  MetalsLogger.updateDefaultFormat()
  val inputs = InputProperties.default()
  val classpath = new SemanticdbClasspath(inputs.sourceroot, inputs.classpath)
  val documents: List[(AbsolutePath, TextDocument)] =
    inputs.scalaFiles.map { input =>
      (input.file, classpath.textDocument(input.file).get)
    }

  val scalaLibrarySourcesJar: AbsolutePath = inputs.dependencySources.entries
    .find(_.toNIO.getFileName.toString.contains("scala-library"))
    .getOrElse {
      sys.error(
        s"missing: scala-library-sources.jar, obtained ${inputs.dependencySources}"
      )
    }

  val jdk = Classpath(JdkSources().toList)
  val fullClasspath = jdk ++ inputs.dependencySources

  val inflated = Inflated.jars(fullClasspath)

  val scalaDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".scala"))
    scribe.info(s"Scala lines: ${result.linesOfCode}")
    result
  }

  val javaDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".java"))
    scribe.info(s"Java lines: ${result.linesOfCode}")
    result
  }

  val megaSources = Classpath(
    Libraries.suite
      .flatMap(_.sources().entries)
      .filter(_.toNIO.getFileName.toString.endsWith(".jar"))
  )
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def mtagsScalaIndex(): Unit = {
    scalaDependencySources.inputs.foreach { input =>
      Mtags.index(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def toplevelsScalaIndex(): Unit = {
    scalaDependencySources.inputs.foreach { input =>
      Mtags.toplevels(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalaTokenize(): Unit = {
    scalaDependencySources.inputs.foreach { input =>
      val scanner = new LegacyScanner(input, meta.dialects.Scala212)
      var i = 0
      scanner.foreach(_ => i += 1)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalacTokenize(): Unit = {
    val g = global
    scalaDependencySources.inputs.foreach { input =>
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
    scalaDependencySources.inputs.foreach { input =>
      import scala.meta._
      val tree = input.parse[Source].get
    }
  }

  lazy val global = InteractiveSemanticdb.newCompiler()

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalacParse(): Unit = {
    val g = global
    scalaDependencySources.inputs.foreach { input =>
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
  def mtagsJavaParse(): Unit = {
    javaDependencySources.inputs.foreach { input =>
      Mtags.index(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def indexSources(): Unit = {
    val index = OnDemandSymbolIndex()
    fullClasspath.entries.foreach(entry => index.addSourceJar(entry))
  }

}
