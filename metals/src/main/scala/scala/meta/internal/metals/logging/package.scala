package scala.meta.internal.metals

import scribe.Level
import scribe.LogFeature
import scribe.mdc.MDC

package object logging {

  @inline
  private def scribeLogWhen(
      level: Level,
      condition: Boolean,
      msg: LogFeature,
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: MDC,
  ): Unit =
    if (condition)
      scribe.log(level, mdc, msg)

  @inline
  def logErrorWhen(condition: Boolean, msg: LogFeature)(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: MDC,
  ): Unit = scribeLogWhen(Level.Error, condition, msg)
}
