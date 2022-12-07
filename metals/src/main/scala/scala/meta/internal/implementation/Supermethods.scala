package scala.meta.internal.implementation

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.implementation.Supermethods.formatMethodSymbolForQuickPick
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentPositionParams

class Supermethods(
    client: MetalsLanguageClient,
    definitionProvider: DefinitionProvider,
    implementationProvider: ImplementationProvider,
)(implicit
    ec: ExecutionContext
) {

  def getGoToSuperMethodCommand(
      commandParams: TextDocumentPositionParams
  ): Option[ExecuteCommandParams] = {
    getGoToSuperMethodLocation(commandParams)
      .map(location =>
        ClientCommands.GotoLocation.toExecuteCommandParams(
          ClientCommands.WindowLocation(location.getUri(), location.getRange())
        )
      )
  }

  def jumpToSelectedSuperMethod(
      commandParams: TextDocumentPositionParams
  ): Future[Unit] = {
    def execute(
        methodSymbols: List[String],
        path: AbsolutePath,
    ): Future[Unit] = {
      askUserToSelectSuperMethod(methodSymbols)
        .map(
          _.flatMap(findDefinitionLocation(_, path))
            .map(location =>
              ClientCommands.GotoLocation.toExecuteCommandParams(
                ClientCommands
                  .WindowLocation(location.getUri(), location.getRange())
              )
            )
            .foreach(client.metalsExecuteClientCommand)
        )
    }

    (for {
      filePath <- commandParams.getTextDocument.getUri.toAbsolutePathSafe
      methodsHierarchy <- getSuperMethodHierarchySymbols(commandParams)
      if methodsHierarchy.nonEmpty
    } yield execute(methodsHierarchy, filePath))
      .getOrElse(Future.successful(()))
  }

  def getGoToSuperMethodLocation(
      params: TextDocumentPositionParams
  ): Option[Location] = {
    for {
      filePath <- params.getTextDocument.getUri.toAbsolutePathSafe
      (symbolOcc, textDocument) <- definitionProvider.symbolOccurrence(
        filePath,
        params.getPosition(),
      )
      findSymbol = implementationProvider.defaultSymbolSearch(
        filePath,
        textDocument,
      )
      symbolInformation <- findSymbol(symbolOcc.symbol)
      gotoSymbol <- {
        if (symbolOcc.role.isDefinition) {
          findSuperMethodSymbol(symbolInformation)
        } else {
          Some(symbolInformation.symbol)
        }
      }
      jumpToLocation <- findDefinitionLocation(gotoSymbol, filePath)
    } yield jumpToLocation
  }

  private def askUserToSelectSuperMethod(
      methodSymbols: List[String]
  ): Future[Option[String]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          methodSymbols
            .map(symbol =>
              MetalsQuickPickItem(
                symbol,
                formatMethodSymbolForQuickPick(symbol),
              )
            )
            .asJava,
          placeHolder = "Select super method to jump to",
        )
      )
      .asScala
      .mapOptionInside(_.itemId)
  }

  def getSuperMethodHierarchySymbols(
      params: TextDocumentPositionParams
  ): Option[List[String]] =
    getSuperMethodHierarchySymbols(
      params.getTextDocument.getUri,
      params.getPosition,
    )

  def getSuperMethodHierarchySymbols(
      path: String,
      position: Position,
  ): Option[List[String]] = {
    for {
      filePath <- path.toAbsolutePathSafe
      (symbolOcc, textDocument) <- definitionProvider.symbolOccurrence(
        filePath,
        position,
      )
      findSymbol = implementationProvider.defaultSymbolSearch(
        filePath,
        textDocument,
      )
      symbolInformation <- findSymbol(symbolOcc.symbol)
      docText = TextDocumentWithPath(textDocument, filePath)
    } yield SuperMethodProvider.getSuperMethodHierarchy(
      symbolInformation
    )
  }

  private def findSuperMethodSymbol(
      symbolInformation: SymbolInformation
  ): Option[String] = {
    SuperMethodProvider.findSuperForMethodOrField(
      symbolInformation
    )
  }

  private def findDefinitionLocation(
      symbol: String,
      source: AbsolutePath,
  ): Option[Location] = {
    definitionProvider.fromSymbol(symbol, Some(source)).asScala.headOption
  }

}

object Supermethods {

  /**
   * Formats method symbol to be nicely displayed in QuickPick for user.
   * Tests visualizing how this method works are in class SuperMethodSuite.
   *
   * @param symbol  Symbol string from semanticdb which represents method.
   * @return
   */
  def formatMethodSymbolForQuickPick(symbol: String): String = {
    val replaced = symbol
      .replace("/", ".")
      .replaceAll("\\(.*\\)", "")
    replaced.substring(0, replaced.length - 1)
  }

}
