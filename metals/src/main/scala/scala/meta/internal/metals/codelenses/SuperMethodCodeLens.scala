package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import scala.collection.{mutable => m}

import scala.meta.internal.implementation.ClassHierarchyItem
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.SuperMethodProvider
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codelenses.SuperMethodCodeLens.LensGoSuperCache
import scala.meta.internal.metals.codelenses.SuperMethodCodeLens.emptyLensGoSuperCache
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence

import org.eclipse.{lsp4j => l}

final class SuperMethodCodeLens(
    implementationProvider: ImplementationProvider,
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    clientConfig: ClientConfiguration
) extends CodeLens {

  override def isEnabled: Boolean = userConfig().superMethodLensesEnabled

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath

    val search = implementationProvider.defaultSymbolSearchMemoize(
      path,
      textDocument
    )
    val distance = buffers.tokenEditDistance(path, textDocument.text)

    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition
      symbol = occurrence.symbol
      gotoSuperMethod <- createSuperMethodCommand(
        textDocumentWithPath,
        symbol,
        occurrence.role,
        emptyLensGoSuperCache(),
        search
      ).toIterable
      range <-
        occurrence.range
          .flatMap(r => distance.toRevised(r.toLSP))
          .toList
    } yield new l.CodeLens(range, gotoSuperMethod, null)
  }

  private def createSuperMethodCommand(
      docWithPath: TextDocumentWithPath,
      symbol: String,
      role: SymbolOccurrence.Role,
      cache: LensGoSuperCache,
      findSymbol: String => Option[SymbolInformation]
  ): Option[l.Command] = {
    for {
      symbolInformation <- findSymbol(symbol)
      gotoParentSymbol <- SuperMethodProvider.findSuperForMethodOrField(
        symbolInformation,
        docWithPath,
        role,
        findSymbol,
        cache
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
      s"${clientConfig.initialConfig.icons.findsuper} ${name}",
      ServerCommands.GotoLocation.id,
      singletonList(symbol)
    )
  }

}

object SuperMethodCodeLens {
  type LensGoSuperCache =
    m.Map[String, List[ClassHierarchyItem]]

  def emptyLensGoSuperCache(): LensGoSuperCache = m.Map()

}
