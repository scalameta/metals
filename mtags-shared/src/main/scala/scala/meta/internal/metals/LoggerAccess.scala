package scala.meta.internal.metals

// Proxy for different logging mechanism java.util.logging in PresentatilnCompiler and scribe in metals
case class LoggerAccess(
    debug: String => Unit,
    info: String => Unit,
    warning: String => Unit,
    error: String => Unit
)

object LoggerAccess {
  object system
      extends LoggerAccess(
        debug = System.out.println(_),
        info = System.out.println(_),
        warning = System.err.println(_),
        error = System.err.println(_)
      )
}
