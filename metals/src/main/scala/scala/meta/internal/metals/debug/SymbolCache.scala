package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

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
          Future.successful(cachedSymbolInfo)
        } else {
          fetchAndCacheSymbolInfo(path, symbol)
        }
      case None =>
        fetchAndCacheSymbolInfo(path, symbol)
    }
  }

  private def fetchAndCacheSymbolInfo(
      path: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    val requestKey = (path, symbol)
    pendingCompilerInfoRequests.get(requestKey) match {
      case Some(existingFuture) =>
        existingFuture
      case None =>
        val compilerInfoFuture =
          compilers().info(path, symbol).map { compilerResult =>
            processCompilerResult(
              symbol,
              path,
              compilerResult,
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
  ): Option[PcSymbolInformation] = {
    import scala.meta.internal.mtags

    symbolIndex.definition(mtags.Symbol(symbol)) match {
      case Some(definition) =>
        symbolInfoCache.put(symbol, (compilerResult, definition.path))
        symbolDefinitionCache.updateWith(definition.path) { existing =>
          Some(existing.getOrElse(Set.empty) + symbol)
        }
        compilerResult

      case None =>
        symbolInfoCache.put(symbol, (compilerResult, path))
        compilerResult
    }
  }

  def clear(): Unit = {
    symbolInfoCache.clear()
    symbolDefinitionCache.clear()
    pendingCompilerInfoRequests.clear()
  }

  def removeSymbolsForPath(path: AbsolutePath): Unit = {
    symbolDefinitionCache.get(path) match {
      case Some(symbols) =>
        symbols.foreach(symbolInfoCache.remove)
        symbolDefinitionCache.remove(path)
      case None => ()
    }
  }

}
