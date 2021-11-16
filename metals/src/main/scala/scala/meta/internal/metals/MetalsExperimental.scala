package scala.meta.internal.metals

/**
 * Language Server Protocol extensions that are declared as "server
 * capabilities" in the initialize response.
 */

object MetalsExperimental {
  // A workaround for https://github.com/microsoft/language-server-protocol/issues/377 until it is resolved
  val rangeHoverProvider: java.lang.Boolean = true
}
