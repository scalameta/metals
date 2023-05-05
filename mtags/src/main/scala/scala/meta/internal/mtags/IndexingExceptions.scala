package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath

object IndexingExceptions {
  class InvalidJarException(val path: AbsolutePath, underlying: Throwable)
      extends Exception(path.toString, underlying)

  class InvalidSymbolException(val symbol: String, underlying: Throwable)
      extends Exception(symbol, underlying)

  class PathIndexingException(val path: AbsolutePath, underlying: Throwable)
      extends Exception(path.toString(), underlying)
}
