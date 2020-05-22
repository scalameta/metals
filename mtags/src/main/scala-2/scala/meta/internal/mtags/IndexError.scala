package scala.meta.internal.mtags

import scala.util.control.NoStackTrace

import scala.meta.io.AbsolutePath

case class IndexError(file: AbsolutePath, cause: Throwable)
    extends Exception(file.toURI.toString, cause)
    with NoStackTrace
