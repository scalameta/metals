package scala.meta.internal.jpc

import java.io.Closeable
import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.ReportLevel
import scala.meta.pc.EmbeddedClient
import scala.meta.pc.OffsetParams
import scala.meta.pc.ParentSymbols
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.ProgressBars
import scala.meta.pc.RangeParams
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.slf4j.Logger

class JavaMetalsCompiler(
    val buildTargetId: String,
    val logger: Logger,
    val reportsLevel: ReportLevel,
    val search: SymbolSearch,
    val embedded: EmbeddedClient,
    val semanticdbFileManager: SemanticdbFileManager,
    val metalsConfig: PresentationCompilerConfig,
    val classpath: Seq[Path],
    val options: List[String],
    val progressBars: ProgressBars,
    val sh: Option[ScheduledExecutorService] = None
) extends Closeable {
  var lastVisitedParentTrees: List[TreePath] = Nil
  private lazy val prune = new JavaPruneCompiler(
    logger,
    reportsLevel,
    semanticdbFileManager,
    embedded,
    progressBars
  )

  def doSearch(query: String): Seq[String] = {
    val v = new JavaCompletionSearchVisitor()
    // Allow importing from anywhere, not only the current build target.
    // TODO: Make this configurable?
    search.search(query, /* buildTargetId= */ "", v)
    v.visitedFQN
  }

  def guessOriginalUri(source: JavaFileObject): String = {
    prune.originalURIs.get(source) match {
      case Some(value) => value
      case None =>
        val name = source.getName()
        // HACK: see SourceJavaFileObject.makeOriginalURI() for an explanation
        // why we use the `getName()` method instead of `toUri()` to recover the
        // original URI.
        if (name.startsWith("originaluri-")) {
          name.stripPrefix("originaluri-")
        } else {
          source.toUri().toString()
        }
    }
  }

  def compilerTreeNode(
      scanner: JavaTreeScanner,
      position: CursorPosition
  ): Option[TreePath] = {
    scanner.scan(scanner.root, position)
    lastVisitedParentTrees = scanner.lastVisitedParentTrees
    lastVisitedParentTrees.headOption
  }

  def compileTask(
      params: VirtualFileParams,
      extraClasspath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): JavaSourceCompile = {
    prune.compileTask(
      params,
      extraClasspath ++ classpath,
      extraOptions ++ options
    )
  }

  def compilationTask(
      params: VirtualFileParams,
      extraClasspath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): JavaSourceCompile = {
    prune.compileTask(
      params,
      extraClasspath ++ classpath,
      extraOptions ++ options
    )
  }
  def batchCompilationTask(
      params: List[VirtualFileParams],
      extraClasspath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): JavaSourceCompile = {
    prune.batchCompileTask(
      params,
      extraClasspath ++ classpath,
      extraOptions ++ options
    )
  }

  def nodeAtPosition(
      params: OffsetParams,
      extraClasspath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): Option[(JavaSourceCompile, TreePath)] = {
    val task = compilationTask(
      params,
      extraClasspath,
      extraOptions
    ).withAnalyzePhase()
    val scanner = new JavaTreeScanner(logger, task.task, task.cu)
    val position = params match {
      case p: RangeParams =>
        CursorPosition(p.offset(), p.offset(), p.endOffset())
      case p: OffsetParams =>
        CursorPosition(p.offset(), p.offset(), p.offset())
    }
    for {
      treePath <- compilerTreeNode(scanner, position).orElse {
        logger.warn(s"JavaMetalsGlobal: no tree path found for ${params.uri()}")
        None
      }
    } yield (task, treePath)
  }
  override def close(): Unit = {
    prune.close()
  }

  def classpathCompilationTask(
      javaFileObject: List[JavaFileObject],
      out: Option[Writer],
      allOptions: List[String],
      diagnosticListener: Option[DiagnosticListener[JavaFileObject]] = None
  ): JavacTask = {
    prune.compiler
      .getTask(
        out.orNull,
        null,
        diagnosticListener.getOrElse(NoopDiagnosticListener),
        allOptions.asJava,
        null,
        javaFileObject.asJava
      )
      .asInstanceOf[JavacTask]
  }

  def documentation(
      task: JavacTask,
      element: Element
  ): String = {
    val sym = SemanticdbSymbol.fromElement(element)
    val doc = search.documentation(
      sym,
      new ParentSymbols {
        override def parents(): ju.List[String] = {
          element match {
            case executableElement: ExecutableElement =>
              element.getEnclosingElement match {
                case enclosingElement: TypeElement =>
                  overriddenSymbols(
                    task,
                    executableElement,
                    enclosingElement
                  )
                case _ => ju.Collections.emptyList[String]
              }
            case _ => ju.Collections.emptyList[String]
          }
        }
      },
      metalsConfig.hoverContentType()
    )

    Option(doc.orElse(null)) match {
      case Some(value) => value.docstring()
      case None => ""
    }
  }

  // NOTE: this function probably needs a reimplementation. It may not handle
  // tricker cases like implementing a supermethod from a grandparent class.
  private def overriddenSymbols(
      task: JavacTask,
      executableElement: ExecutableElement,
      enclosingElement: TypeElement
  ): ju.List[String] = {
    val types = task.getTypes()
    val elements = task.getElements()
    val overriddenSymbols = for {
      // get superclasses
      superType <- types.directSupertypes(enclosingElement.asType()).asScala
      superElement = types.asElement(superType)
      // get elements of superclass
      enclosedElement <- superElement match {
        case typeElement: TypeElement =>
          typeElement.getEnclosedElements().asScala
        case _ => Nil
      }
      // filter out non-methods
      enclosedExecutableElement <- enclosedElement match {
        case enclosedExecutableElement: ExecutableElement =>
          Some(enclosedExecutableElement)
        case _ => None
      }
      // check super method overrides original method
      if (elements.overrides(
        executableElement,
        enclosedExecutableElement,
        enclosingElement
      ))
    } yield SemanticdbSymbol.fromElement(enclosedExecutableElement)
    overriddenSymbols.toList.asJava
  }
}

object JavaMetalsCompiler {
  val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()
  val STANDARD_FILE_MANAGER: StandardJavaFileManager =
    COMPILER.getStandardFileManager(null, null, StandardCharsets.UTF_8)
  def parse(params: VirtualFileParams): Option[(Trees, CompilationUnitTree)] = {
    parse(SourceJavaFileObject.fromParams(params))
  }
  def parse(source: JavaFileObject): Option[(Trees, CompilationUnitTree)] = {
    val printer = new StringWriter()
    val task = COMPILER.getTask(
      printer,
      STANDARD_FILE_MANAGER,
      null,
      List.empty[String].asJava,
      null,
      List(source).asJava
    )
    val elems = task.asInstanceOf[JavacTask].parse()
    val trees = Trees.instance(task)
    val iter = elems.iterator
    // only one CompilationUnitTree for a single file
    if (iter.hasNext()) Some((trees, iter.next()))
    else None
  }

  def makeFileObject(file: File): JavaFileObject = {
    val files =
      STANDARD_FILE_MANAGER.getJavaFileObjectsFromFiles(List(file).asJava)
    files.iterator().next()
  }
  def classpathCompilationTask(
      javaFileObject: JavaFileObject,
      out: Option[Writer],
      allOptions: List[String]
  ): JavacTask = {
    COMPILER
      .getTask(
        out.orNull,
        null,
        NoopDiagnosticListener,
        allOptions.asJava,
        null,
        List(javaFileObject).asJava
      )
      .asInstanceOf[JavacTask]
  }

}
