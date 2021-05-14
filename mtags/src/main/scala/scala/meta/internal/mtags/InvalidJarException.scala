package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath

case class InvalidJarException(path: AbsolutePath, underlying: Throwable)
    extends Exception(path.toString)
