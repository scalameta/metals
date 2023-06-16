package scala.meta.internal.metals.codelenses

import scala.meta.internal.implementation.SuperMethodProvider
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final class SuperMethodCodeLens(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    clientConfig: ClientConfiguration,
    trees: Trees,
) extends CodeLens {

  override def isEnabled: Boolean = userConfig().superMethodLensesEnabled

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath

    def search(query: String) = textDocument.symbols.find(_.symbol == query)

    val distance = buffers.tokenEditDistance(path, textDocument.text, trees)

    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition
      symbol = occurrence.symbol
      gotoSuperMethod <- createSuperMethodCommand(
        symbol,
        search,
        textDocument,
        path,
      ).toIterable
      range <-
        occurrence.range
          .flatMap(r => distance.toRevisedStrict(r).map(_.toLsp))
          .toList
    } yield new l.CodeLens(range, gotoSuperMethod, null)
  }

  private def createSuperMethodCommand(
      symbol: String,
      findSymbol: String => Option[SymbolInformation],
      textDocument: TextDocument,
      path: AbsolutePath,
  ): Option[l.Command] = {
    for {
      symbolInformation <- findSymbol(symbol)
      gotoParentSymbol <- SuperMethodProvider.findSuperForMethodOrField(
        symbolInformation
      )
      command <- convertToSuperMethodCommand(
        gotoParentSymbol,
        symbolInformation.displayName,
        textDocument,
        path,
      )
    } yield command
  }

  private def convertToSuperMethodCommand(
      symbol: String,
      name: String,
      textDocument: TextDocument,
      path: AbsolutePath,
  ): Option[l.Command] = {
    if (symbol.isLocal)
      textDocument.occurrences.collectFirst {
        case SymbolOccurrence(
              Some(range),
              `symbol`,
              SymbolOccurrence.Role.DEFINITION,
            ) =>
          val location = new l.Location(path.toURI.toString(), range.toLsp)
          val command = ServerCommands.GotoPosition.toLsp(location)
          command.setTitle(s"${clientConfig.icons.findsuper} ${name}")
          command
      }
    else
      Some {
        val command = ServerCommands.GotoSymbol.toLsp(symbol)
        command.setTitle(s"${clientConfig.icons.findsuper} ${name}")
        command
      }
  }

}
