package scala.meta.internal.metals.callHierarchy

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.{semanticdb => s}

import org.eclipse.{lsp4j => l}
import scala.meta.Tree
import scala.meta.Defn
import scala.meta.Pat
import scala.meta.Name
import scala.meta.Member
import scala.meta.Term
import scala.meta.Decl
import scala.meta.Type
import scala.meta.Init
import scala.meta.Template

/** Utility functions for call hierarchy requests. */
private[callHierarchy] trait CallHierarchyHelpers {
  private def extractNameFromDefinitionPats[V <: Tree { def pats: List[Pat] }](
      v: V
  ) =
    v.pats match {
      case (pat: Pat.Var) :: Nil => Some((v, pat.name))
      case _ => None
    }

  def extractNameFromDefinition(tree: Tree): Option[(Tree, Name)] =
    tree match {
      case v: Defn.Val => extractNameFromDefinitionPats(v)
      case v: Defn.Var => extractNameFromDefinitionPats(v)
      case v: Decl.Val => extractNameFromDefinitionPats(v)
      case v: Decl.Var => extractNameFromDefinitionPats(v)
      case member: Member => Some((member, member.name))
      case _ => None
    }

  /** Type declarations are not considered in call hierarchy request. This function helps to filter them */
  def isTypeDeclaration(tree: Tree): Boolean =
    (tree.parent
      .map {
        case t: Template => t.inits.contains(tree)
        case p: Term.Param => p.decltpe.contains(tree)
        case at: Term.ApplyType => at.targs.contains(tree)
        case p: Type.Param => p.tbounds == tree
        case v: Defn.Val => v.decltpe.contains(tree)
        case v: Defn.Var => v.decltpe.contains(tree)
        case ga: Defn.GivenAlias => ga.decltpe == tree
        case d: Defn.Def =>
          d.decltpe.contains(tree) || d.tparams.contains(tree)
        case _: Type.Bounds => true
        case t @ (_: Type | _: Name | _: Init) => isTypeDeclaration(t)
        case _ => false
      })
      .getOrElse(false)

  def findDefinition(from: Option[Tree]): Option[(Tree, Name)] =
    from
      .filterNot(tree => tree.is[Term.Param] || isTypeDeclaration(tree))
      .flatMap(tree =>
        extractNameFromDefinition(tree) match {
          case result @ Some(_) => result
          case None => findDefinition(tree.parent)
        }
      )

  def getSignatureFromHover(hover: Option[l.Hover]): Option[String] =
    (for {
      hover <- hover
      hoverContent <- hover.getContents().asScala.toOption
      `match` <- """Symbol signature\*\*:\n```scala\n(.*)\n```""".r
        .findFirstMatchIn(hoverContent.getValue)
    } yield `match`.group(1))

  def containsDuplicates[T](visited: Seq[T]) =
    visited.view
      .scanLeft(Set.empty[T])((set, a) => set + a)
      .zip(visited.view)
      .exists { case (set, a) => set contains a }

  def extractSelectTree(tree: s.Tree) =
    tree match {
      case selectTree: s.SelectTree => Some(selectTree)
      case s.TypeApplyTree(selectTree: s.SelectTree, _) => Some(selectTree)
      case _ => None
    }
}
