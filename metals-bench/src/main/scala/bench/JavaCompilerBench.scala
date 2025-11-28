package bench

import java.io.StringWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

import scala.collection.mutable.ArrayBuffer
import scala.util.Using

import scala.meta.inputs.Input
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.io.AbsolutePath

import com.sun.source.tree.MethodInvocationTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tests.Library

@State(Scope.Benchmark)
class JavaCompilerBench {
  MetalsLogger.updateDefaultFormat()

  val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()
  val fileManager: StandardJavaFileManager =
    COMPILER.getStandardFileManager(null, null, null)
  var library: Library = _
  val tmp: AbsolutePath = AbsolutePath(
    Files.createTempDirectory("metals-header-compiler")
  )
  val headerCompilerJar: AbsolutePath = new Embedded(tmp).javaHeaderCompiler
  var inputs: ArrayBuffer[Input.VirtualFile] =
    ArrayBuffer.empty[Input.VirtualFile]
  var linesOfCode: Long = 0
  var sout = new StringWriter()
  lazy val flamegraphs = new Flamegraphs(s"javac-compiler-bench")

  @Setup
  def setup(): Unit = {
    flamegraphs.setup()
    sout.close()
    sout = new StringWriter()
    library = Library.springbootStarterWeb
    library.sources.entries.foreach(path => {
      Using(new JarFile(path.toNIO.toFile)) { jar =>
        for {
          entry <- jar.entries().asScala
          if entry.getName.endsWith(".java")
        } {
          val text = new String(
            jar.getInputStream(entry).readAllBytes(),
            StandardCharsets.UTF_8,
          )
          linesOfCode += text.linesIterator.length
          inputs += Input.VirtualFile(entry.getName, text)
        }
      }
    })
    println(f"Lines of code: $linesOfCode%,d")
  }

  @TearDown
  def teardown(): Unit = {
    flamegraphs.tearDown()
    tmp.deleteRecursively()
  }

  def getTask(isHeaderCompilerEnabled: Boolean): JavacTask = {
    val sources = inputs
      .map(i => SourceJavaFileObject.make(i.text, URI.create(i.path.toString)))
      .asJava
    val noopDiagnosticListener = new DiagnosticListener[JavaFileObject] {
      override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = {
        // noop
      }
    }
    val options = List.newBuilder[String]
    if (isHeaderCompilerEnabled)
      options += "-Xplugin:MetalsHeaderCompiler"
    options += "-verbose"
    options += "-d"
    options += tmp.toString
    options += "-classpath"
    options += headerCompilerJar.toString
    COMPILER
      .getTask(
        sout,
        fileManager,
        noopDiagnosticListener,
        options.result().asJava,
        null,
        sources,
      )
      .asInstanceOf[JavacTask]
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javacGenerate(): Unit = {
    getTask(isHeaderCompilerEnabled = false).generate()
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javacAnalyze(): Unit = {
    getTask(isHeaderCompilerEnabled = false)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javacHeaderGenerate(): Unit = {
    getTask(isHeaderCompilerEnabled = true).generate()
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javacHeaderAnalyze(): Unit = {
    getTask(isHeaderCompilerEnabled = true).analyze()
    println(sout.toString)
  }

  // Basic visitor just to do a walk on the parsed ASTs instead of only parsing
  // and not using the trees.
  class MethodInvocationVisitor extends TreeScanner[Unit, Unit] {
    var treeNodes = 0
    override def visitMethodInvocation(
        node: MethodInvocationTree,
        _unused: Unit,
    ): Unit = {
      treeNodes += 1
      super.visitMethodInvocation(node, ())
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javacParse(): Unit = {
    val v = new MethodInvocationVisitor
    getTask(isHeaderCompilerEnabled = false).parse().asScala.foreach { tree =>
      tree.accept(v, ())
    }
    println(v.treeNodes)
  }

}
