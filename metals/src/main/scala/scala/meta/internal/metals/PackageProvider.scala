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
    packageStatement(path).map(workspaceEdit(path, _))
  }

  private def packageStatement(path: AbsolutePath): Option[String] = {

    def packageObjectStatement(path: Iterator[Path]): Option[String] = {
      val pathList = path.toList
      val packageDeclaration =
        if (pathList.size > 1)
          s"package ${pathList.dropRight(1).mkString(".")}\n\n"
        else ""
      pathList.lastOption.map { packageObjectName =>
        s"""|${packageDeclaration}package object $packageObjectName {
            |  
            |}
            |""".stripMargin
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
            Some(s"package $packageName\n\n")
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
    val changes = Map(path.toString -> textEdits).asJava
    new WorkspaceEdit(changes)
  }
}
