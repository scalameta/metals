package scala.meta.internal.jpc

import java.io.Closeable
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.ReportLevel
import scala.meta.pc.EmbeddedClient
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.OffsetParams
import scala.meta.pc.ParentSymbols
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.ProgressBars
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Position
import org.slf4j.Logger

class JavaMetalsCompiler(
    val buildTargetId: String,
    val logger: Logger,
    val reportsLevel: ReportLevel,
    val search: SymbolSearch,
    val embedded: EmbeddedClient,
    val javaFileManagerFactory: JavaFileManagerFactory,
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
    javaFileManagerFactory,
    embedded,
    progressBars,
    metalsConfig.javacServicesOverrides(),
    classpath
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

  def isAtIdentifier(
      treePath: TreePath,
      element: Element,
      text: String,
      offset: Int,
      trees: Trees,
      root: CompilationUnitTree
  ): Boolean = {
    val leaf = treePath.getLeaf()
    val sourcePositions = trees.getSourcePositions()
    val treeStart = sourcePositions.getStartPosition(root, leaf)
    val treeEnd = sourcePositions.getEndPosition(root, leaf)
    if (treeStart >= 0 && treeEnd >= 0) {
      val elementName = element.getSimpleName().toString()
      val (start, end) = findIndentifierStartAndEnd(
        text,
        elementName,
        treeStart.toInt,
        treeEnd.toInt,
        leaf,
        root,
        sourcePositions
      )
      start <= offset && end >= offset
    } else false
  }

  def offsetToPosition(offset: Int, text: String): Position = {
    var line = 0
    var character = 0
    var i = 0
    while (i < offset && i < text.length()) {
      if (text.charAt(i) == '\n') {
        line += 1
        character = 0
      } else {
        character += 1
      }
      i += 1
    }
    new Position(line, character)
  }

  def nodeAtPosition(
      params: OffsetParams,
      extraClasspath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil,
      forReferences: Boolean = false
  ): Option[(JavaSourceCompile, TreePath)] = {
    val task = compilationTask(
      params,
      extraClasspath,
      extraOptions
    ).withAnalyzePhase()
    val scanner = new JavaTreeScanner(logger, task.task, task.cu, forReferences)
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

  /**
   * Return the real start and end for the name. For definitions the start and end include the whole element.
   *
   * @param text
   * @param elementName
   * @param originalStart
   * @param originalEnd
   */
  /**
   * The name of the element as it appears in source. For constructors
   * `getSimpleName` returns the internal `<init>`, so we fall back to the
   * enclosing type's name, which is what is actually written in the source.
   */
  def sourceName(element: Element): String =
    if (element.getKind() == ElementKind.CONSTRUCTOR)
      element.getEnclosingElement().getSimpleName().toString()
    else
      element.getSimpleName().toString()

  def findIndentifierStartAndEnd(
      text: String,
      elementName: String,
      originalStart: Int,
      originalEnd: Int,
      leaf: Tree,
      root: CompilationUnitTree,
      sourcePositions: SourcePositions
  ): (Int, Int) =
    if (originalEnd - originalStart == elementName.length()) {
      (originalStart, originalEnd)
    } else {
      val declarationStart = leaf match {
        case mt: MethodTree if mt.getReturnType() != null =>
          sourcePositions.getEndPosition(root, mt.getReturnType())
        case vt: VariableTree =>
          sourcePositions.getEndPosition(root, vt.getType())
        case _ =>
          originalStart
      }

      val subText = text.substring(declarationStart.toInt, originalEnd)
      val nameIndex = subText.indexOf(elementName)
      if (nameIndex >= 0) {
        val nameStart = declarationStart + nameIndex
        val nameEnd = nameStart + elementName.length()
        (nameStart.toInt, nameEnd.toInt)
      } else {
        (originalStart, originalEnd)
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

}
