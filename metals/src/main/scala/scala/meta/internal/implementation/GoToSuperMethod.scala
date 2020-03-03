package scala.meta.internal.implementation

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position

import scala.collection.{mutable => m}
import scala.meta.internal.implementation.GoToSuperMethod.GoToSuperMethodParams
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

class GoToSuperMethod(
    definitionProvider: DefinitionProvider,
    implementationProvider: ImplementationProvider,
    superMethodProvider: SuperMethodProvider,
    buildTargets: BuildTargets
) {

  def getGoToSuperMethodCommand(
      commandParams: ExecuteCommandParams
  ): Option[ExecuteCommandParams] = {
    parseJsonParams(commandParams)
      .flatMap(getGoToSuperMethodLocation)
      .map(makeCommandParams)
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
      jumpToLocation <- {
        if (symbolOcc.role.isDefinition) {
          val docText = TextDocumentWithPath(textDocument, filePath)
          findSuperMethodLocation(
            symbolInformation,
            symbolOcc.role,
            docText,
            findSymbol
          )
        } else {
          findDefinitionLocation(symbolInformation.symbol)
        }
      }
    } yield jumpToLocation
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
            .orElse(
              global.info(si)
            )
        )
  }

  private def findSuperMethodLocation(
      symbolInformation: SymbolInformation,
      role: SymbolOccurrence.Role,
      docText: TextDocumentWithPath,
      findSymbol: String => Option[SymbolInformation]
  ): Option[Location] = {
    superMethodProvider.findSuperForMethodOrField(
      symbolInformation,
      docText,
      role,
      findSymbol,
      m.Map()
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

  case class GoToSuperMethodParams(document: String, position: Position)

}
