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
  ): Future[List[TypeHierarchyItem]] =
    getItemInfo(params.getItem.getData) match {
      case None => Future.successful(Nil)
      case Some(itemInfo) =>
        val symbol = itemInfo.symbol
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

            val (localItems, externalSymbols) = parentSymbols.partitionMap {
              parentSymbol =>
                doc.occurrences
                  .find(o => o.symbol == parentSymbol && o.role.isDefinition)
                  .flatMap(_.range)
                  .map { range =>
                    Left(
                      itemBuilder.build(
                        symbol = parentSymbol,
                        info = doc.symbols.find(_.symbol == parentSymbol),
                        source = path,
                        range = range.toLsp,
                        selectionRange = range.toLsp,
                      )
                    )
                  }
                  .getOrElse(Right(parentSymbol))
            }

            val externalLocations = externalSymbols.flatMap { parentSymbol =>
              definitionProvider
                .fromSymbol(parentSymbol, None)
                .asScala
                .headOption
                .map(loc => (parentSymbol, loc))
            }

            val externalItems = externalLocations
              .groupBy { case (_, loc) => loc.getUri.toAbsolutePath }
              .toList
              .flatMap { case (locPath, symbolsWithLocs) =>
                semanticdbs()
                  .textDocument(locPath)
                  .documentIncludingStale
                  .toList
                  .flatMap { locDoc =>
                    symbolsWithLocs.map { case (parentSymbol, location) =>
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

            Future.successful(localItems ++ externalItems)
          case None =>
            Future.successful(Nil)
        }
    }

  def subtypes(
      params: TypeHierarchySubtypesParams
  ): Future[List[TypeHierarchyItem]] =
    getItemInfo(params.getItem.getData) match {
      case None => Future.successful(Nil)
      case Some(itemInfo) =>
        val symbol = itemInfo.symbol
        val source = params.getItem.getUri.toAbsolutePath
        implementationProvider
          .findImplementationsBySymbol(symbol, source)
          .map { classLocations =>
            classLocations
              .groupBy(_.file)
              .toList
              .flatMap { case (filePathOpt, locs) =>
                for {
                  filePath <- filePathOpt.toList
                  absPath = AbsolutePath(filePath)
                  doc <- semanticdbs()
                    .textDocument(absPath)
                    .documentIncludingStale
                    .toList
                  classLoc <- locs
                  occ <- doc.occurrences
                    .find(o =>
                      o.symbol == classLoc.symbol && o.role.isDefinition
                    )
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

  private def getItemInfo(data: Object): Option[TypeHierarchyItemInfo] =
    Option(data) match {
      case None =>
        scribe.warn("No item data provided for type hierarchy request")
        None
      case Some(d) =>
        d.asInstanceOf[JsonElement]
          .as[TypeHierarchyItemInfo]
          .toOption
          .orElse {
            scribe.warn(s"Failed to parse TypeHierarchyItemInfo from: $data")
            None
          }
    }
}
