package scala.meta.internal.metals.callHierarchy

import scala.meta.Name
import scala.meta.Tree

/**
 * Where to start looking for occurrences.
 * When using the findLastEnclosingAt method, it often happens that you get Name, not the root of the definition,
 * this trait allows to store the root and the potential name of a definition.
 */
private[callHierarchy] sealed trait RealRoot {
  def root: Tree
  def name: Option[Name]
}

private[callHierarchy] case class NamedRealRoot(
    root: Tree,
    private val _name: Name,
) extends RealRoot {
  val name: Some[Name] = Some(_name)
}

private[callHierarchy] case class UnamedRealRoot(root: Tree) extends RealRoot {
  val name = None
}
