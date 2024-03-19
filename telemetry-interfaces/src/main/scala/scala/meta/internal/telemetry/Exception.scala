package scala.meta.internal.telemetry

case class ExceptionSummary(
    exception: String,
    stacktrace: List[StackTraceElement],
)

case class StackTraceElement(
    fullyQualifiedName: String,
    methodName: String,
    errorFile: String,
    errorLine: Int,
)

object ExceptionSummary {
  def from(e: Throwable, sanitize: String => String): ExceptionSummary = {
    val stackTraceElements = e
      .getStackTrace()
      .toList
      .flatMap(element => {
        for {
          className <- Option(element.getClassName)
          methodName <- Option(element.getMethodName)
          errorLine <- Option(element.getLineNumber)
          errorFile <- Option(element.getFileName)
        } yield StackTraceElement(
          sanitize(className),
          sanitize(methodName),
          sanitize(errorFile),
          errorLine,
        )
      })

    ExceptionSummary(sanitize(e.getMessage), stackTraceElements)
  }

}
