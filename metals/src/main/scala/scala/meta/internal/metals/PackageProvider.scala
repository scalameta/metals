package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class PackageProvider(private val buildTargets: BuildTargets) {

  def workspaceEdit(path: AbsolutePath): Option[WorkspaceEdit] = {
    packageName(path).map(workspaceEdit(path, _))
  }

  private def packageName(path: AbsolutePath): Option[String] = {
    // package.scala file needs to be handled separately
    // as its package name is different than package name of normal scala file
    if (path.isScala &&
      path.toFile.length() == 0 &&
      path.filename != "package.scala") {
      val sourceItem = buildTargets.inverseSourceItem(path)
      val relativeDirectory = sourceItem
        .map(path.toRelative)
        .flatMap(relativePath => Option(relativePath.toNIO.getParent))
        .map(_.toString)

      relativeDirectory.map(_.replace("/", "."))
    } else {
      None
    }
  }

  private def workspaceEdit(
      path: AbsolutePath,
      packageName: String
  ): WorkspaceEdit = {
    val textEdit = new TextEdit(
      new Range(new Position(0, 0), new Position(0, 0)),
      s"package $packageName\n\n"
    )

    val textEdits = List(textEdit).asJava
    val changes = Map(path.toString -> textEdits).asJava
    new WorkspaceEdit(changes)
  }
}
