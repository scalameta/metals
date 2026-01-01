package scala.meta.internal.pc

import java.util.Optional

import scala.jdk.CollectionConverters._

import scala.meta.pc.AutoImportsResult
import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Provider for auto-imports in Java files.
 */
class JavaAutoImportsProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams,
    name: String,
    buildTargetIdentifier: String
) {

  def autoImports(): List[AutoImportsResult] = {
    val task: JavacTask = compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val root: CompilationUnitTree = scanner.root

    val results = List.newBuilder[AutoImportsResult]

    val visitor = new JavaClassVisitor(
      task.getElements,
      element => {
        val simpleName = element.getSimpleName.toString
        if (simpleName == name) {
          val fullName = element.toString
          val packageName = extractPackageName(fullName)

          val identifierRange = computeIdentifierRange()
          val edits = AutoImports.computeAutoImportEdits(
            compiler,
            task,
            root,
            fullName,
            identifierRange
          )

          val allEdits =
            edits.identifierEdit.toList ++ edits.importTextEdits

          results += new AutoImportsResultImpl(
            packageName,
            allEdits.asJava,
            Optional.of(fullName)
          )

          true
        } else {
          false
        }
      }
    )

    compiler.search.search(name, buildTargetIdentifier, visitor)

    results.result()
  }

  /**
   * Extract package name from full class name.
   * Example: "java.util.List" -> "java.util"
   */
  private def extractPackageName(fullName: String): String = {
    val lastDot = fullName.lastIndexOf('.')
    if (lastDot > 0) fullName.substring(0, lastDot) else ""
  }

  private def computeIdentifierRange(): Range = {
    val text = params.text()
    val length = text.length

    val start = math.max(0, math.min(params.offset(), length))
    val end = math.min(start + name.length, length)

    new Range(
      compiler.offsetToPosition(start, text),
      compiler.offsetToPosition(end, text)
    )
  }
}

private class AutoImportsResultImpl(
    packageNameValue: String,
    editsValue: java.util.List[TextEdit],
    symbolValue: Optional[String]
) extends AutoImportsResult {
  override def packageName(): String = packageNameValue
  override def edits(): java.util.List[TextEdit] = editsValue
  override def symbol(): Optional[String] = symbolValue
}
