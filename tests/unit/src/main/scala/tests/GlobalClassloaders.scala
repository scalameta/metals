package tests

import scala.meta.internal.metals.Embedded

/**
 * Global state to reuse classloaders between test suites.
 */
object GlobalClassloaders {
  lazy val bloop = Embedded.newBloopClassloader()
}
