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
    symbolIndex: OnDemandSymbolIndex,
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
          scribe.info(
            s"[${Thread.currentThread().getId}] Cache hit for symbol '$symbol' at path '$path'. Cache state: $symbolInfoCache"
          )
          Future.successful(cachedSymbolInfo)
        } else {
          scribe.info(
            s"[${Thread.currentThread().getId}] Cache miss for symbol '$symbol' - cached path '$cachedPath' != current path '$path'. Cache state: $symbolInfoCache"
          )
          fetchAndCacheSymbolInfo(path, symbol)
        }
      case None =>
        scribe.info(
          s"[${Thread.currentThread().getId}] Cache miss for symbol '$symbol' - not found in cache. Cache state: $symbolInfoCache"
        )
        fetchAndCacheSymbolInfo(path, symbol)
    }
  }

  private def fetchAndCacheSymbolInfo(
      path: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    import scala.meta.internal.mtags

    val originalThreadId = Thread.currentThread().getId
    scribe.info(
      s"[${Thread.currentThread().getId}] Fetching and caching symbol info for '$symbol' at path '$path'"
    )
    compilers().info(path, symbol).map { result =>
      symbolIndex.definition(mtags.Symbol(symbol)) match {
        case Some(definition) =>
          scribe.info(
            s"[${Thread.currentThread().getId}][$originalThreadId] Found definition for '$symbol' at path '${definition.path}'"
          )
          symbolInfoCache.get(symbol) match {
            case Some((existingInfo, existingPath)) =>
              scribe.info(
                s"[${Thread.currentThread().getId}][$originalThreadId] Overwriting existing cache entry for '$symbol': old path '$existingPath' -> new path '${definition.path}', existing info: $existingInfo, new info: $result"
              )
            case None =>
              scribe.info(
                s"[${Thread.currentThread().getId}][$originalThreadId] Adding new cache entry for '$symbol' at path '${definition.path}', new info: $result"
              )
          }
          symbolInfoCache.put(symbol, (result, definition.path))
          symbolDefinitionCache.updateWith(definition.path) { existing =>
            Some(existing.getOrElse(Set.empty) + symbol)
          }
          result
        case None =>
          scribe.info(
            s"[${Thread.currentThread().getId}][$originalThreadId] No definition found for '$symbol'"
          )
          // Fallback to original behavior if definition not found
          symbolInfoCache.put(symbol, (result, path))
          result
      }
    }
  }

  def clear(): Unit = {
    scribe.info(s"[${Thread.currentThread().getId}] Clearing cache")
    symbolInfoCache.clear()
    symbolDefinitionCache.clear()
  }

  def removeSymbolsForPath(path: AbsolutePath): Unit = {
    symbolDefinitionCache.get(path) match {
      case Some(symbols) =>
        scribe.info(
          s"[${Thread.currentThread().getId}] Removing ${symbols.size} cached symbols for path '$path': ${symbols.mkString(", ")}"
        )
        symbols.foreach(symbolInfoCache.remove)
        symbolDefinitionCache.remove(path)
      case None =>
        scribe.info(
          s"[${Thread.currentThread().getId}] No cached symbols found to remove for path '$path'"
        )
    }
  }

}
