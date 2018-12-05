package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath
import scala.util.control.NoStackTrace

case class IndexError(file: AbsolutePath, cause: Throwable)
    extends Exception(file.toURI.toString, cause)
    with NoStackTrace
