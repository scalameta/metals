package scala.meta.languageserver

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.{Global, Response}
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import langserver.messages.MessageType
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Document

class Compiler(
    out: PrintStream,
    config: Observable[AbsolutePath],
    connection: Connection,
    buffers: Buffers
)(implicit cwd: AbsolutePath, s: Scheduler)
    extends LazyLogging {
  private val documentPubSub =
    Observable.multicast[Document](MulticastStrategy.Publish)
  private val documentSubscriber = documentPubSub._1
  private val indexedJars: java.util.Set[AbsolutePath] =
    ConcurrentHashMap.newKeySet[AbsolutePath]()
  val documentPublisher: Observable[Document] = documentPubSub._2
  val onNewCompilerConfig: Observable[Unit] =
    config
      .map(path => CompilerConfig.fromPath(path))
      .flatMap { config =>
        Observable.merge(
          Observable.delay(loadNewCompilerGlobals(config)),
          Observable.delay(indexDependencyClasspath(config))
        )
      }

  def autocomplete(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): List[(String, String)] = {
    logger.info(s"Completion request at $path:$line:$column")
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).fold(noCompletions) { compiler =>
      compiler.reporter.reset()
      val source = code.take(offset) + "_CURSOR_" + code.drop(offset)
      val unit = compiler.newCompilationUnit(source, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      logger.info(s"Completion request at position $position")
      val results = compiler.completionsAt(position).matchingResults()
      results
        .map(r => (r.sym.signatureString, r.symNameDropLocal.decoded))
        .distinct
    }
  }

  def typeAt(path: AbsolutePath, line: Int, column: Int): Option[String] = {
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).flatMap { compiler =>
      compiler.reporter.reset()
      val unit = compiler.newCompilationUnit(code, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      val response = ask[compiler.Tree](r => compiler.askTypeAt(position, r))
      val typedTree = response.get.swap
      typedTree.toOption.flatMap(t => typeOfTree(compiler)(t))
    }
  }

  private val compilerByPath = mutable.Map.empty[AbsolutePath, Global]
  private def loadNewCompilerGlobals(config: CompilerConfig): Unit = {
    logger.info(s"Loading new compiler from config $config")
    val vd = new io.VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = config.classpath
    settings.processArgumentString(
      ("-Ypresentation-any-thread" :: config.scalacOptions).mkString(" ")
    )
    val compiler = new Global(settings, new StoreReporter)
    config.sources.foreach { path =>
      // TODO(olafur) garbage collect compilers from removed files.
      compilerByPath(path) = compiler
    }
  }
  private def indexDependencyClasspath(config: CompilerConfig): Unit = {
    val sourcesClasspath = Jars
      .fetch(config.libraryDependencies, out, sources = true)
      .filterNot(jar => indexedJars.contains(jar))
    import scala.collection.JavaConverters._
    indexedJars.addAll(sourcesClasspath.asJava)
    if (sourcesClasspath.nonEmpty) {
      logger.info(
        s"Indexing classpath ${sourcesClasspath.mkString(File.pathSeparator)}"
      )
    }
    Ctags.index(sourcesClasspath) { doc =>
      documentSubscriber.onNext(doc)
    }
  }
  private def noCompletions: List[(String, String)] = {
    connection.showMessage(
      MessageType.Warning,
      "Run project/config:scalametaEnableCompletions to setup completion for this " +
        "config.in(project) or *:scalametaEnableCompletions for all projects/configurations"
    )
    Nil
  }
  private def lineColumnToOffset(
      contents: String,
      line: Int,
      column: Int
  ): Int = {
    var i = 0
    var l = line
    while (l > 0) {
      if (contents(i) == '\n') l -= 1
      i += 1
    }
    i + column
  }

  private def ask[A](f: Response[A] => Unit): Response[A] = {
    val r = new Response[A]
    f(r)
    r
  }

  private def typeOfTree(c: Global)(t: c.Tree): Option[String] = {
    import c._

    val refinedTree = t match {
      case t: ImplDef if t.impl != null => t.impl
      case t: ValOrDefDef if t.tpt != null => t.tpt
      case t: ValOrDefDef if t.rhs != null => t.rhs
      case x => x
    }

    Option(refinedTree.tpe).map(_.widen.toString)
  }

}
