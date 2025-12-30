package scala.meta.internal.metals.typeHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.io.AbsolutePath

import com.google.gson.JsonElement
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams

final class TypeHierarchyProvider(
    semanticdbs: () => Semanticdbs,
    definitionProvider: DefinitionProvider,
    implementationProvider: ImplementationProvider,
)(implicit ec: ExecutionContext) {

  private val itemBuilder = new TypeHierarchyItemBuilder()

  def prepare(
      params: TypeHierarchyPrepareParams
  ): Future[List[TypeHierarchyItem]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs().textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results = definitionProvider
          .positionOccurrences(source, params.getPosition, doc)
          .flatMap { rso =>
            for {
              occ <- rso.occurrence
              if doc.symbols.find(_.symbol == occ.symbol).exists { info =>
                info.isClass || info.isTrait || info.isObject || info.isType
              }
              range <- occ.range
            } yield {
              itemBuilder.build(
                symbol = occ.symbol,
                info = doc.symbols.find(_.symbol == occ.symbol),
                source = source,
                range = range.toLsp,
                selectionRange = range.toLsp,
              )
            }
          }
        Future.successful(results.toList)
      case None =>
        Future.successful(Nil)
    }
  }

  def supertypes(
      params: TypeHierarchySupertypesParams
  ): Future[List[TypeHierarchyItem]] = {
    val symbol =
      getItemInfo(params.getItem.getData).symbol
    val path = params.getItem.getUri.toAbsolutePath

    semanticdbs().textDocument(path).documentIncludingStale match {
      case Some(doc) =>
        val parentSymbols = doc.symbols
          .find(_.symbol == symbol)
          .toList
          .flatMap { info =>
            ImplementationProvider
              .parentsFromSignature(symbol, info.signature, Some(path))
              .map(_._1)
          }

        Future.successful(
          parentSymbols.flatMap { parentSymbol =>
            doc.occurrences
              .find(o => o.symbol == parentSymbol && o.role.isDefinition)
              .flatMap(_.range)
              .map { range =>
                itemBuilder.build(
                  symbol = parentSymbol,
                  info = doc.symbols.find(_.symbol == parentSymbol),
                  source = path,
                  range = range.toLsp,
                  selectionRange = range.toLsp,
                )
              }
              .orElse {
                definitionProvider
                  .fromSymbol(parentSymbol, None)
                  .asScala
                  .headOption
                  .flatMap { location =>
                    val locPath = location.getUri.toAbsolutePath
                    semanticdbs()
                      .textDocument(locPath)
                      .documentIncludingStale
                      .map { locDoc =>
                        itemBuilder.build(
                          symbol = parentSymbol,
                          info = locDoc.symbols.find(_.symbol == parentSymbol),
                          source = locPath,
                          range = location.getRange,
                          selectionRange = location.getRange,
                        )
                      }
                  }
              }
          }
        )
      case None =>
        Future.successful(Nil)
    }
  }

  def subtypes(
      params: TypeHierarchySubtypesParams
  ): Future[List[TypeHierarchyItem]] = {
    val symbol =
      getItemInfo(params.getItem.getData).symbol
    val source = params.getItem.getUri.toAbsolutePath

    implementationProvider
      .findImplementationsBySymbol(symbol, source)
      .map { classLocations =>
        classLocations.flatMap { classLoc =>
          for {
            filePath <- classLoc.file
            absPath = AbsolutePath(filePath)
            doc <- semanticdbs().textDocument(absPath).documentIncludingStale
            occ <- doc.occurrences
              .find(o => o.symbol == classLoc.symbol && o.role.isDefinition)
            range <- occ.range
          } yield {
            itemBuilder.build(
              symbol = classLoc.symbol,
              info = doc.symbols.find(_.symbol == classLoc.symbol),
              source = absPath,
              range = range.toLsp,
              selectionRange = range.toLsp,
            )
          }
        }
      }
  }

  private def getItemInfo(data: Object): TypeHierarchyItemInfo =
    data
      .asInstanceOf[JsonElement]
      .as[TypeHierarchyItemInfo]
      .get
}
