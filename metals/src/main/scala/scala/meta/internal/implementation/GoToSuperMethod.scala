package scala.meta.internal.implementation

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.implementation.GoToSuperMethod.GoToSuperMethodParams
import scala.meta.internal.implementation.GoToSuperMethod.formatMethodSymbolForQuickPick
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.CodeLensProvider.emptyLensGoSuperCache
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsQuickPickItem
import scala.meta.internal.metals.MetalsQuickPickParams
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

class GoToSuperMethod(
    client: MetalsLanguageClient,
    definitionProvider: DefinitionProvider,
    implementationProvider: ImplementationProvider,
    superMethodProvider: SuperMethodProvider,
    buildTargets: BuildTargets
)(
    implicit ec: ExecutionContext
) {

  def getGoToSuperMethodCommand(
      commandParams: ExecuteCommandParams
  ): Option[ExecuteCommandParams] = {
    parseJsonParams(commandParams)
      .flatMap(getGoToSuperMethodLocation)
      .map(makeCommandParams)
  }

  def jumpToSelectedSuperMethod(
      commandParams: ExecuteCommandParams
  ): Future[Unit] = {
    def execute(methodSymbols: List[String]): Future[Unit] = {
      askUserToSelectSuperMethod(methodSymbols)
        .map(
          _.flatMap(findDefinitionLocation)
            .map(makeCommandParams)
            .foreach(client.metalsExecuteClientCommand)
        )
    }

    (for {
      params <- parseJsonParams(commandParams)
      methodsHierarchy <- getSuperMethodHierarchySymbols(params)
      if methodsHierarchy.nonEmpty
    } yield execute(methodsHierarchy)).getOrElse(Future.successful())
  }

  def getGoToSuperMethodLocation(
      params: GoToSuperMethodParams
  ): Option[Location] = {
    for {
      filePath <- params.document.toAbsolutePathSafe
      (symbolOcc, textDocument) <- definitionProvider.symbolOccurrence(
        filePath,
        params.position
      )
      findSymbol = makeFindSymbolMethod(textDocument, filePath)
      symbolInformation <- findSymbol(symbolOcc.symbol)
      gotoSymbol <- {
        if (symbolOcc.role.isDefinition) {
          val docText = TextDocumentWithPath(textDocument, filePath)
          findSuperMethodSymbol(
            symbolInformation,
            symbolOcc.role,
            docText,
            findSymbol
          )
        } else {
          Some(symbolInformation.symbol)
        }
      }
      jumpToLocation <- findDefinitionLocation(gotoSymbol)
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
                formatMethodSymbolForQuickPick(symbol)
              )
            )
            .asJava,
          placeHolder = "Select super method to jump to"
        )
      )
      .asScala
      .map {
        case pickResult if !pickResult.cancelled => Some(pickResult.itemId)
        case _ => None
      }
  }

  def getSuperMethodHierarchySymbols(
      params: GoToSuperMethodParams
  ): Option[List[String]] = {
    for {
      filePath <- params.document.toAbsolutePathSafe
      (symbolOcc, textDocument) <- definitionProvider.symbolOccurrence(
        filePath,
        params.position
      )
      findSymbol = makeFindSymbolMethod(textDocument, filePath)
      symbolInformation <- findSymbol(symbolOcc.symbol)
      docText = TextDocumentWithPath(textDocument, filePath)
      hierarchy <- superMethodProvider.getSuperMethodHierarchy(
        symbolInformation,
        docText,
        symbolOcc.role,
        findSymbol
      )
    } yield hierarchy.map(_.symbol)
  }

  private def makeFindSymbolMethod(
      textDocument: TextDocument,
      filePath: AbsolutePath
  ): String => Option[SymbolInformation] = {
    val global = new GlobalClassTable(buildTargets)
      .globalSymbolTableFor(filePath)
      .get
    si =>
      ImplementationProvider
        .findSymbol(textDocument, si)
        .orElse(
          implementationProvider
            .findSymbolInformation(si)
        )
        .orElse(
          global.info(si)
        )
  }

  private def findSuperMethodSymbol(
      symbolInformation: SymbolInformation,
      role: SymbolOccurrence.Role,
      docText: TextDocumentWithPath,
      findSymbol: String => Option[SymbolInformation]
  ): Option[String] = {
    superMethodProvider.findSuperForMethodOrField(
      symbolInformation,
      docText,
      role,
      findSymbol,
      emptyLensGoSuperCache()
    )
  }

  private def findDefinitionLocation(symbol: String): Option[Location] = {
    definitionProvider.fromSymbol(symbol).asScala.headOption
  }

  private def makeCommandParams(location: Location): ExecuteCommandParams = {
    new ExecuteCommandParams(
      ClientCommands.GotoLocation.id,
      List[Object](location).asJava
    )
  }

  private def parseJsonParams(
      commandParams: ExecuteCommandParams
  ): Option[GoToSuperMethodParams] = {
    for {
      args <- Option(commandParams.getArguments)
      argObject <- args.asScala.headOption
      superMethodParams <- argObject.toJsonObject
        .as[GoToSuperMethodParams]
        .toOption
    } yield superMethodParams
  }
}

object GoToSuperMethod {

  final case class GoToSuperMethodParams(document: String, position: Position)

  def formatMethodSymbolForQuickPick(symbol: String): String = {
    val replaced = symbol
      .replace("/", ".")
      .replaceAll("\\(.*\\)", "")
    replaced.substring(0, replaced.length - 1)
  }

}
