package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.QualifiedNameable
import javax.tools.Diagnostic.Kind.ERROR

import scala.jdk.CollectionConverters._

import com.sun.source.tree.ClassTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.DocTrees
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

private class UnusedImportDiagnosticProvider(
    compile: JavaSourceCompile
) {

  private val task = compile.task
  private val trees = Trees.instance(task)
  private val docTrees = DocTrees.instance(task)
  private val sourcePositions = trees.getSourcePositions()
  private val sourceUri = compile.cu.getSourceFile().toUri()

  def diagnostics(): List[l.Diagnostic] = {
    val seen = scala.collection.mutable.Set.empty[String]

    val imports = compile.cu
      .getImports()
      .asScala
      .toList
      .filterNot(hasError)
      .map { importTree =>
        val info = UnusedImportDiagnosticProvider.ImportInfo(
          importTree,
          element(importTree.getQualifiedIdentifier())
        )
        info.copy(isDuplicate = !seen.add(info.normalized))
      }

    if (imports.isEmpty) Nil
    else {
      val usedElements = scala.collection.mutable.Set.empty[Element]
      val usedNames = scala.collection.mutable.Set.empty[String]
      val javadocNames = scala.collection.mutable.Set.empty[String]

      val scanner = new TreePathScanner[Unit, Unit] {
        override def visitImport(node: ImportTree, p: Unit): Unit = ()

        override def visitClass(node: ClassTree, p: Unit): Unit = {
          addJavadocReferences(getCurrentPath(), javadocNames)
          super.visitClass(node, p)
        }

        override def visitIdentifier(
            node: IdentifierTree,
            p: Unit
        ): Unit = {
          usedNames += node.getName().toString()
          addElement(getCurrentPath(), usedElements)
          super.visitIdentifier(node, p)
        }

        override def visitMethod(node: MethodTree, p: Unit): Unit = {
          addJavadocReferences(getCurrentPath(), javadocNames)
          super.visitMethod(node, p)
        }

        override def visitVariable(node: VariableTree, p: Unit): Unit = {
          addJavadocReferences(getCurrentPath(), javadocNames)
          super.visitVariable(node, p)
        }
      }
      scanner.scan(compile.cu, ())

      imports.collect {
        case info
            if info.isRedundant(packageName) ||
              info.isDuplicate ||
              !info.isUsed(
                usedElements.toSet,
                usedNames.toSet,
                javadocNames.toSet
              ) =>
          val importTree = info.tree
          diagnostic(importTree)
      }
    }
  }

  private def packageName: Option[String] =
    Option(compile.cu.getPackageName()).map(_.toString())

  private def hasError(importTree: ImportTree): Boolean = {
    val start = sourcePositions.getStartPosition(compile.cu, importTree)
    val end = sourcePositions.getEndPosition(compile.cu, importTree)
    if (start < 0 || end < 0) false
    else {
      compile.listener.diagnostics.exists { d =>
        val diagnosticStart = d.getPosition()
        val diagnosticEnd = math.max(diagnosticStart, d.getEndPosition())
        val sameSource =
          d.getSource() != null && d.getSource().toUri() == sourceUri
        val overlapsImport =
          diagnosticStart <= end && diagnosticEnd >= start

        d.getKind() == ERROR && sameSource && overlapsImport
      }
    }
  }

  private def addJavadocReferences(
      path: TreePath,
      names: scala.collection.mutable.Set[String]
  ): Unit =
    for {
      docComment <- Option(docTrees.getDocCommentTree(path))
      comment = docComment.toString()
      regex <- UnusedImportDiagnosticProvider.JavadocReferenceRegexes
      reference <- regex.findAllMatchIn(comment)
    } addJavadocReference(reference.group(1), names)

  private def addJavadocReference(
      signature: String,
      names: scala.collection.mutable.Set[String]
  ): Unit = {
    val withoutMember = signature.takeWhile(_ != '#')
    val withoutParams = withoutMember.takeWhile(_ != '(')
    val name = withoutParams.stripSuffix("[]").trim()
    if (name.nonEmpty) {
      names += name
      names += name.split('.').last
    }
  }

  private def addElement(
      path: TreePath,
      usedElements: scala.collection.mutable.Set[Element]
  ): Unit =
    Option(trees.getElement(path)).foreach(usedElements += _)

  private def element(tree: Tree): Option[Element] = {
    Option(trees.getPath(compile.cu, tree))
      .flatMap(path => Option(trees.getElement(path)))
  }

  private def diagnostic(importTree: ImportTree): l.Diagnostic = {
    val range = Positions.toLspRange(trees, compile.cu, importTree)
    val diagnostic = new l.Diagnostic(
      range,
      "unused import",
      l.DiagnosticSeverity.Warning,
      "javac"
    )
    diagnostic.setCode(UnusedImportDiagnosticProvider.UnusedImportCode)
    diagnostic
  }
}

object UnusedImportDiagnosticProvider {
  val UnusedImportCode = "unused-import"
  private val InlineJavadocReferenceRegex =
    """\{@(?:link|linkplain|value)\s+([^\s#(}]+)""".r
  private val SeeJavadocReferenceRegex = """@see\s+([^\s#(]+)""".r
  private val JavadocReferenceRegexes =
    List(InlineJavadocReferenceRegex, SeeJavadocReferenceRegex)

  private case class ImportInfo(
      tree: ImportTree,
      isStatic: Boolean,
      isWildcard: Boolean,
      owner: String,
      name: String,
      fullName: String,
      normalized: String,
      element: Option[Element],
      isDuplicate: Boolean
  ) {
    def isRedundant(packageName: Option[String]): Boolean =
      !isStatic &&
        (owner == "java.lang" || packageName.contains(owner))

    def isUsed(
        usedElements: Set[Element],
        usedNames: Set[String],
        javadocNames: Set[String]
    ): Boolean =
      if (isWildcard) {
        usedElements.exists { element =>
          val usedOwner =
            if (isStatic)
              UnusedImportDiagnosticProvider.enclosingName(element)
            else UnusedImportDiagnosticProvider.name(element)
          usedOwner.exists(name =>
            name == owner || name.startsWith(owner + ".")
          )
        }
      } else {
        element.exists(usedElements.contains) ||
        (isStatic && usedNames.contains(name)) ||
        javadocNames.exists(name => name == this.name || name == fullName)
      }
  }

  private object ImportInfo {
    def apply(tree: ImportTree, element: Option[Element]): ImportInfo = {
      val importText = tree.getQualifiedIdentifier().toString()
      val isWildcard = importText.endsWith(".*")
      val withoutWildcard =
        if (isWildcard) importText.stripSuffix(".*") else importText
      val (owner, name) =
        if (isWildcard) withoutWildcard -> "*"
        else {
          val lastDot = withoutWildcard.lastIndexOf(".")
          if (lastDot >= 0)
            withoutWildcard.substring(0, lastDot) ->
              withoutWildcard.substring(lastDot + 1)
          else "" -> withoutWildcard
        }
      val normalized = s"${tree.isStatic()}:$importText"
      ImportInfo(
        tree,
        tree.isStatic(),
        isWildcard,
        owner,
        name,
        withoutWildcard,
        normalized,
        element,
        isDuplicate = false
      )
    }
  }

  private def name(element: Element): Option[String] =
    element match {
      case qualified: QualifiedNameable =>
        Some(qualified.getQualifiedName().toString())
      case _ => None
    }

  private def enclosingName(element: Element): Option[String] =
    Option(element.getEnclosingElement()).flatMap(name)
}
