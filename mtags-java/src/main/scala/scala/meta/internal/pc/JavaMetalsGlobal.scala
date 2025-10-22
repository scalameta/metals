package scala.meta.internal.pc

import java.io.File
import java.io.Writer
import java.net.URI
import java.nio.file.Path
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath

class JavaMetalsGlobal(
    val search: SymbolSearch,
    val metalsConfig: PresentationCompilerConfig,
    val classpath: Seq[Path]
) {
  var lastVisitedParentTrees: List[TreePath] = Nil

  def compilerTreeNode(
      scanner: JavaTreeScanner,
      position: CursorPosition
  ): Option[TreePath] = {
    scanner.scan(scanner.root, position)
    lastVisitedParentTrees = scanner.lastVisitedParentTrees
    lastVisitedParentTrees.headOption
  }

  def compilationTask(sourceCode: String, uri: URI): JavacTask = {
    val javaFileObject = SourceJavaFileObject.make(sourceCode, uri)
    JavaMetalsGlobal.classpathCompilationTask(
      javaFileObject,
      None,
      List("-classpath", classpath.mkString(File.pathSeparator))
    )
  }

  def semanticdbSymbol(element: Element): String = {

    @tailrec
    def descriptors(
        acc: List[Descriptor],
        element: Element
    ): List[Descriptor] = {
      if (element == null || element.getSimpleName.toString == "") {
        if (acc.isEmpty) Empty :: Nil
        else acc
      } else {
        val elements = {
          element match {
            case packageElement: PackageElement =>
              packageElement.getQualifiedName.toString
                .split('.')
                .map(Package(_))
                .toList
            case executableElement: ExecutableElement =>
              List(
                Method(
                  executableElement.getSimpleName().toString(),
                  disambiguator(executableElement)
                )
              )
            case typeElement: TypeElement =>
              List(Class(typeElement.getSimpleName().toString()))
            case typeParameterElement: TypeParameterElement =>
              List(
                TypeVariable(typeParameterElement.getSimpleName().toString())
              )
            case variableElement: VariableElement =>
              List(Var(variableElement.getSimpleName().toString()))
            case _ => List(Empty)
          }
        }

        descriptors(elements ::: acc, element.getEnclosingElement())
      }
    }

    val decs = descriptors(Nil, element).filter(_ != Empty)

    (decs match {
      case Nil => List.empty[Descriptor]
      case d @ (Package(_) :: _) => d
      case d => Package("_empty_") :: d
    }).mkString("")
  }

  private def disambiguator(executableElement: ExecutableElement): String = {
    val methods =
      executableElement.getEnclosingElement.getEnclosedElements.asScala
        .collect {
          case e: ExecutableElement
              if e.getSimpleName == executableElement.getSimpleName =>
            e
        }

    val index = methods.zipWithIndex.collectFirst {
      case (e, i) if e.equals(executableElement) => i
    }

    index match {
      case Some(i) => if (i == 0) "()" else s"(+$i)"
      case None => "()"
    }
  }

  object Symbols {
    val None: String = ""
    val RootPackage: String = "_root_/"
  }

}

object JavaMetalsGlobal {

  private val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()

  private val noopDiagnosticListener = new DiagnosticListener[JavaFileObject] {

    // ignore errors since presentation compiler will have a lot of transient ones
    override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = ()
  }

  def makeFileObject(file: File): JavaFileObject = {
    val fileManager = COMPILER.getStandardFileManager(null, null, null)
    val files = fileManager.getJavaFileObjectsFromFiles(List(file).asJava)
    files.iterator().next()
  }

  def baseCompilationTask(sourceCode: String, uri: URI): JavacTask = {
    val javaFileObject = SourceJavaFileObject.make(sourceCode, uri)
    JavaMetalsGlobal.classpathCompilationTask(javaFileObject, None, Nil)
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
        noopDiagnosticListener,
        allOptions.asJava,
        null,
        List(javaFileObject).asJava
      )
      .asInstanceOf[JavacTask]
  }

  def scanner(task: JavacTask): JavaTreeScanner = {
    val elems = task.parse()
    task.analyze()
    val root = elems.iterator().next()

    new JavaTreeScanner(task, root)
  }
}
