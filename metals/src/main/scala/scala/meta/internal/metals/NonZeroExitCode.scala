package scala.meta.internal.metals

case class NonZeroExitCode(statusCode: Int)
    extends Exception(s"Expected status code 0. Obtained code $statusCode")
