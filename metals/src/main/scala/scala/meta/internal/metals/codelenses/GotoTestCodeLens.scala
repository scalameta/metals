package scala.meta.internal.metals.codelenses

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.GotoTestProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.{lsp4j => l}

final class GotoTestCodeLens(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    gotoTestProvider: GotoTestProvider,
    trees: Trees,
)(implicit val ec: ExecutionContext)
    extends CodeLens {

  override def isEnabled: Boolean = userConfig().gotoTestLensesEnabled

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Future[Seq[l.CodeLens]] = Future {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath

    val distance = buffers.tokenEditDistance(path, textDocument.text, trees)

    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition && occurrence.symbol.isType
      if gotoTestProvider.hasTarget(occurrence.symbol, path)
      name = Symbol(occurrence.symbol).displayName
      title =
        if (GotoTestProvider.isTestClass(name)) "Go to Test Subject"
        else "Go to Test"
      range <- occurrence.range
        .flatMap(r => distance.toRevisedStrict(r).map(_.toLsp))
        .toList
    } yield {
      val pos =
        new l.Position(range.getStart.getLine, range.getStart.getCharacter)
      val params = new TextDocumentPositionParams(
        new TextDocumentIdentifier(path.toURI.toString),
        pos,
      )
      val command = ServerCommands.GotoTest.toLsp(params)
      command.setTitle(title)
      new l.CodeLens(range, command, null)
    }
  }
}
