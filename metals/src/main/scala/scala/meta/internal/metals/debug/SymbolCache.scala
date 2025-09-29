package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.internal.metals.Compilers
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

  /** Cache for ongoing compiler info requests to avoid duplicate calls */
  private val pendingCompilerInfoRequests =
    TrieMap.empty[(AbsolutePath, String), Future[Option[PcSymbolInformation]]]

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

    val requestKey = (path, symbol)
    val originalThreadId = Thread.currentThread().getId

    scribe.info(
      s"[${Thread.currentThread().getId}] Fetching and caching symbol info for '$symbol' at path '$path'"
    )

    pendingCompilerInfoRequests.get(requestKey) match {
      case Some(existingFuture) =>
        scribe.info(
          s"[${Thread.currentThread().getId}] Reusing existing compiler info request for '$symbol' at path '$path'"
        )
        existingFuture
      case None =>
        val compilerInfoFuture =
          compilers().info(path, symbol).map { compilerResult =>
            processCompilerResult(
              symbol,
              path,
              compilerResult,
              originalThreadId,
            )
          }

        pendingCompilerInfoRequests.put(requestKey, compilerInfoFuture)
        compilerInfoFuture.onComplete { _ =>
          pendingCompilerInfoRequests.remove(requestKey)
        }

        compilerInfoFuture
    }
  }

  private def processCompilerResult(
      symbol: String,
      path: AbsolutePath,
      compilerResult: Option[PcSymbolInformation],
      originalThreadId: Long,
  ): Option[PcSymbolInformation] = {
    import scala.meta.internal.mtags

    symbolIndex.definition(mtags.Symbol(symbol)) match {
      case Some(definition) =>
        scribe.info(
          s"[${Thread.currentThread().getId}][$originalThreadId] Found definition for '$symbol' at path '${definition.path}'"
        )

        symbolInfoCache.get(symbol) match {
          case Some((existingInfo, existingPath)) =>
            scribe.info(
              s"[${Thread.currentThread().getId}][$originalThreadId] Overwriting existing cache entry for '$symbol': old path '$existingPath' -> new path '${definition.path}'"
            )
          case None =>
            scribe.info(
              s"[${Thread.currentThread().getId}][$originalThreadId] Adding new cache entry for '$symbol' at path '${definition.path}'"
            )
        }

        symbolInfoCache.put(symbol, (compilerResult, definition.path))
        symbolDefinitionCache.updateWith(definition.path) { existing =>
          Some(existing.getOrElse(Set.empty) + symbol)
        }
        compilerResult

      case None =>
        scribe.info(
          s"[${Thread.currentThread().getId}][$originalThreadId] No definition found for '$symbol'"
        )
        symbolInfoCache.put(symbol, (compilerResult, path))
        compilerResult
    }
  }

  def clear(): Unit = {
    scribe.info(s"[${Thread.currentThread().getId}] Clearing cache")
    symbolInfoCache.clear()
    symbolDefinitionCache.clear()
    pendingCompilerInfoRequests.clear()
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
