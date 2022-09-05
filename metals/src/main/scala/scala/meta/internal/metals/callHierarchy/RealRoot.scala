package scala.meta.internal.metals.callHierarchy

import scala.meta.Name
import scala.meta.Tree

/**
 * Where to start looking for occurrences.
 * When using the findLastEnclosingAt method, it often happens that you get Name, not the root of the definition,
 * this trait allows to store the root and the potential name of a definition.
 */
private[callHierarchy] case class RealRoot(root: Tree, name: Name)
