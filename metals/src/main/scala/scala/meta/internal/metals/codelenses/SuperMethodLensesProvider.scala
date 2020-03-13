package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import org.eclipse.{lsp4j => l}

import scala.collection.{mutable => m}
import scala.meta.internal.implementation.{
  GlobalClassTable,
  ImplementationProvider,
  SuperMethodProvider,
  SymbolWithAsSeenFrom,
  TextDocumentWithPath
}
import scala.meta.internal.metals.{
  Buffers,
  BuildTargets,
  MetalsServerConfig,
  ServerCommands,
  TokenEditDistance,
  UserConfiguration
}
import scala.meta.internal.semanticdb.{
  SymbolInformation,
  SymbolOccurrence,
  TextDocument
}
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codelenses.SuperMethodLensesProvider.{
  LensGoSuperCache,
  emptyLensGoSuperCache
}

final class SuperMethodLensesProvider(
    implementationProvider: ImplementationProvider,
    buffers: Buffers,
    buildTargets: BuildTargets,
    userConfig: () => UserConfiguration,
    config: MetalsServerConfig
) extends CodeLenses {

  override def isEnabled: Boolean = userConfig().superMethodLensesEnabled

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath

    val search =
      makeSymbolSearchMethod(
        makeGlobalClassTable(textDocumentWithPath.filePath),
        textDocumentWithPath.textDocument
      )
    val distance =
      TokenEditDistance.fromBuffer(path, textDocument.text, buffers)

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
      range <- occurrence.range
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
    if (userConfig().superMethodLensesEnabled) {
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
    } else {
      None
    }
  }

  private def convertToSuperMethodCommand(
      symbol: String,
      name: String
  ): l.Command = {
    new l.Command(
      s"${config.icons.findsuper} ${name}",
      ServerCommands.GotoLocation.id,
      singletonList(symbol)
    )
  }

  private def makeSymbolSearchMethod(
      global: GlobalSymbolTable,
      openedTextDocument: TextDocument
  ): String => Option[SymbolInformation] = { symbolInformation =>
    implementationProvider
      .findSymbolInformation(symbolInformation)
      .orElse(global.info(symbolInformation))
      .orElse(openedTextDocument.symbols.find(_.symbol == symbolInformation))
  }

  private def makeGlobalClassTable(path: AbsolutePath): GlobalSymbolTable = {
    new GlobalClassTable(buildTargets).globalSymbolTableFor(path).get
  }

}

object SuperMethodLensesProvider {
  type LensGoSuperCache =
    m.Map[String, List[SymbolWithAsSeenFrom]]

  def emptyLensGoSuperCache(): LensGoSuperCache = m.Map()

}
