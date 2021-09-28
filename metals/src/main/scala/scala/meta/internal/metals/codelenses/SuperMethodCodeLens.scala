package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.SuperMethodProvider
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.SymbolInformation

import org.eclipse.{lsp4j => l}

final class SuperMethodCodeLens(
    implementationProvider: ImplementationProvider,
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    clientConfig: ClientConfiguration,
    trees: Trees
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
        search
      ).toIterable
      range <-
        occurrence.range
          .flatMap(r => distance.toRevised(r.toLSP))
          .toList
    } yield new l.CodeLens(range, gotoSuperMethod, null)
  }

  private def createSuperMethodCommand(
      symbol: String,
      findSymbol: String => Option[SymbolInformation]
  ): Option[l.Command] = {
    for {
      symbolInformation <- findSymbol(symbol)
      gotoParentSymbol <- SuperMethodProvider.findSuperForMethodOrField(
        symbolInformation
      )
    } yield convertToSuperMethodCommand(
      gotoParentSymbol,
      symbolInformation.displayName
    )
  }

  private def convertToSuperMethodCommand(
      symbol: String,
      name: String
  ): l.Command = {
    new l.Command(
      s"${clientConfig.icons.findsuper} ${name}",
      ServerCommands.GotoSymbol.id,
      singletonList(symbol)
    )
  }

}
