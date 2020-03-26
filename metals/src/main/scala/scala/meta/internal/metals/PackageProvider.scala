package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import java.nio.file.Path

class PackageProvider(private val buildTargets: BuildTargets) {

  def workspaceEdit(path: AbsolutePath): Option[WorkspaceEdit] = {
    packageStatement(path).map(template =>
      workspaceEdit(path, template.fileContent)
    )
  }

  def packageStatement(path: AbsolutePath): Option[NewFileTemplate] = {

    def packageObjectStatement(
        path: Iterator[Path]
    ): Option[NewFileTemplate] = {
      val pathList = path.toList
      val packageDeclaration =
        if (pathList.size > 1)
          s"package ${pathList.dropRight(1).mkString(".")}\n\n"
        else ""
      pathList.lastOption.map { packageObjectName =>
        val indent = "  "
        NewFileTemplate(
          s"""|${packageDeclaration}package object $packageObjectName {
              |${indent}@@
              |}
              |""".stripMargin
        )
      }
    }

    if (path.isScala && path.toFile.length() == 0) {
      buildTargets
        .inverseSourceItem(path)
        .map(path.toRelative)
        .flatMap(relativePath => Option(relativePath.toNIO.getParent))
        .flatMap { parent =>
          val pathIterator = parent.iterator().asScala
          if (path.filename == "package.scala") {
            packageObjectStatement(pathIterator)
          } else {
            val packageName = parent.iterator().asScala.mkString(".")
            Some(NewFileTemplate(s"package $packageName\n\n@@"))
          }
        }
    } else {
      None
    }
  }

  private def workspaceEdit(
      path: AbsolutePath,
      packageStatement: String
  ): WorkspaceEdit = {
    val textEdit = new TextEdit(
      new Range(new Position(0, 0), new Position(0, 0)),
      packageStatement
    )
    val textEdits = List(textEdit).asJava
    val changes = Map(path.toURI.toString -> textEdits).asJava
    new WorkspaceEdit(changes)
  }
}
