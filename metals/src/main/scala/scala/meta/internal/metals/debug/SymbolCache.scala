package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.debug.BuildTargetClasses.TestSymbolInfo
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.io.AbsolutePath

final class SymbolCache(
    compilers: () => Compilers,
    symbolIndex: OnDemandSymbolIndex
)(implicit ec: ExecutionContext) {

  private val symbolInfoCache =
    TrieMap.empty[String, (Option[PcSymbolInformation], AbsolutePath)]

  private val symbolDefinitionCache =
    TrieMap.empty[AbsolutePath, Set[String]]

  def getCachedSymbolInfo(
      path: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    symbolInfoCache.get(symbol) match {
      case Some((cachedSymbolInfo, cachedPath)) =>
        if (cachedPath == path) {
          scribe.info(s"Cache hit for symbol '$symbol' at path '$path'. Cache state: $symbolInfoCache")
          Future.successful(cachedSymbolInfo)
        } else {
          scribe.info(s"Cache miss for symbol '$symbol' - cached path '$cachedPath' != current path '$path'. Cache state: $symbolInfoCache")
          fetchAndCacheSymbolInfo(path, symbol)
        }
      case None =>
        scribe.info(s"Cache miss for symbol '$symbol' - not found in cache. Cache state: $symbolInfoCache")
        fetchAndCacheSymbolInfo(path, symbol)
    }
  }

  private def fetchAndCacheSymbolInfo(
      path: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    import scala.meta.internal.mtags
    
    scribe.info(s"Fetching and caching symbol info for '$symbol' at path '$path'")
    compilers().info(path, symbol).flatMap { result =>
      symbolIndex.definition(mtags.Symbol(symbol)) match {
        case Some(definition) =>
            scribe.info(s"Found definition for '$symbol' at path '$definition.path'")
          symbolInfoCache.put(symbol, (result, definition.path))
          symbolDefinitionCache.updateWith(definition.path) { existing =>
            Some(existing.getOrElse(Set.empty) + symbol)
          }
          Future.successful(result)
        case None =>
            scribe.info(s"No definition found for '$symbol'")
          // Fallback to original behavior if definition not found
          symbolInfoCache.put(symbol, (result, path))
          Future.successful(result)
      }
    }
  }

  def clear(): Unit = {
    scribe.info(s"Clearing cache")
    symbolInfoCache.clear()
    symbolDefinitionCache.clear()
  }


  def removeSymbolsForPath(path: AbsolutePath): Unit = {
    symbolDefinitionCache.get(path) match {
      case Some(symbols) =>
        scribe.info(s"Removing ${symbols.size} cached symbols for path '$path': ${symbols.mkString(", ")}")
        symbols.foreach(symbolInfoCache.remove)
        symbolDefinitionCache.remove(path)
      case None =>
        scribe.info(s"No cached symbols found to remove for path '$path'")
    }
  }

}
