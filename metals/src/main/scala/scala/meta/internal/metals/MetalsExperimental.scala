package scala.meta.internal.metals

/**
 * Language Server Protocol extensions that are declared as "server
 * capabilities" in the initialize response.
 */
case class MetalsExperimental(
    treeViewProvider: java.lang.Boolean = true,
    debuggingProvider: java.lang.Boolean = true
)
