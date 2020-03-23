package scala.meta.internal.metals.codeactions

import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import java.net.URI
import java.nio.file.Paths

class CreateNewFile() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def createNewFile(diagnostic: l.Diagnostic, name: String): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(CreateNewFile.title)
      codeAction.setKind(l.CodeActionKind.QuickFix)
      codeAction.setDiagnostics(List(diagnostic).asJava)
      val directory = Paths
        .get(URI.create(params.getTextDocument().getUri()))
        .getParent()
        .toString()
      codeAction.setCommand(ServerCommands.NewScalaFile.toLSP(List(null, name)))
      codeAction
    }

    val codeActions = params.getContext().getDiagnostics().asScala.collect {
      case d @ ScalacDiagnostic.SymbolNotFound(name)
          if d.getRange().encloses(params.getRange()) =>
        createNewFile(d, name)
    }

    Future.successful(codeActions)

  }
}

object CreateNewFile {
  val title = "Create new..."
}
