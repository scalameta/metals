package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath

object IndexingExceptions {
  case class InvalidJarException(path: AbsolutePath, underlying: Throwable)
      extends Exception(path.toString)

  case class InvalidSymbolException(symbol: String, underlying: Throwable)
      extends Exception(symbol)

  case class PathIndexingException(path: AbsolutePath, underlying: Throwable)
      extends Exception(path.toString())
}
