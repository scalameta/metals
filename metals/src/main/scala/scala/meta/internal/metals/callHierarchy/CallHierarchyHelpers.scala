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
import scala.meta.Type
import scala.meta.Init
import scala.meta.Template

/** Utility functions for call hierarchy requests. */
private[callHierarchy] trait CallHierarchyHelpers {
  def extractNameFromMember(tree: Tree): Option[(Member, Name)] =
    tree match {
      case member: Member => Some((member, member.name))
      case _ => None
    }

  /** Type declarations are not considered in call hierarchy request, this function helps to filter them. */
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

  /**
   * Go up in the tree to find a specified tree or find any definition.
   * If the specified item is founded this function will return a couple that contain the item,
   * otherwise the couple will containm the definition and the name of this definition.
   */
  def getSpecifiedOrFindDefinition(
      from: Option[Tree],
      specified: Option[Tree] = None,
      prev: Option[Tree] = None,
      indices: List[Int] = Nil,
  ): Option[(Tree, Tree)] =
    (specified
      .collect { case tree if from.contains(tree) => (tree, tree) })
      .orElse(
        from
          .filterNot(tree => tree.is[Term.Param] || isTypeDeclaration(tree))
          .flatMap(tree =>
            extractNameFromMember(tree) match {
              case result @ Some(_) => result
              case None =>
                tree match {
                  case v: Defn.Val =>
                    v.pats.headOption.flatMap(pat =>
                      traverseTreeWithIndices(pat, indices)
                        .collect { case pat: Pat.Var =>
                          (v, pat.name)
                        }
                    )
                  case tuple: Term.Tuple =>
                    prev.flatMap(prev =>
                      getSpecifiedOrFindDefinition(
                        tree.parent,
                        specified,
                        Some(tuple),
                        tuple.args.indexOf(prev) :: indices,
                      )
                    )
                  case apply: Term.Apply =>
                    prev.flatMap(prev =>
                      getSpecifiedOrFindDefinition(
                        tree.parent,
                        specified,
                        Some(apply),
                        apply.args.indexOf(prev) :: indices,
                      )
                    )
                  case _ =>
                    getSpecifiedOrFindDefinition(
                      tree.parent,
                      specified,
                      Some(tree),
                    )
                }
            }
          )
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

  def extractNameAndPathsFromPat(
      pat: Pat,
      indices: Vector[Int] = Vector.empty,
  ): List[(Name, Vector[Int])] =
    pat match {
      case v: Pat.Var => List(v.name -> indices)
      case tuple: Pat.Tuple =>
        tuple.args.zipWithIndex.flatMap { case (p, i) =>
          extractNameAndPathsFromPat(p, indices :+ i)
        }
      case extract: Pat.Extract =>
        extract.args.zipWithIndex.flatMap { case (p, i) =>
          extractNameAndPathsFromPat(p, indices :+ i)
        }
      case _ => Nil
    }

  def traverseTreeWithIndices(t: Tree, indices: List[Int]): Option[Tree] =
    indices match {
      case i :: tail =>
        t match {
          case tuple: Term.Tuple =>
            tuple.args
              .lift(i)
              .flatMap(term => traverseTreeWithIndices(term, tail))
          case apply: Term.Apply =>
            apply.args
              .lift(i)
              .flatMap(term => traverseTreeWithIndices(term, tail))
          case tuple: Pat.Tuple =>
            tuple.args
              .lift(i)
              .flatMap(term => traverseTreeWithIndices(term, tail))
          case extract: Pat.Extract =>
            extract.args
              .lift(i)
              .flatMap(term => traverseTreeWithIndices(term, tail))
          case _ => None
        }
      case Nil => Some(t)
    }

  def getIndicesFromPat(
      tree: Tree,
      indices: List[Int] = Nil,
  ): (Tree, List[Int]) = tree match {
    case v: Defn.Val => (v.rhs, indices)
    case v: Defn.Var => (v.rhs.getOrElse(v), indices)
    case _ =>
      tree.parent match {
        case Some(tuple @ (_: Pat.Tuple)) =>
          getIndicesFromPat(tuple, tuple.args.indexOf(tree) :: indices)
        case Some(extract @ (_: Pat.Extract)) =>
          getIndicesFromPat(extract, extract.args.indexOf(tree) :: indices)
        case Some(parent) => getIndicesFromPat(parent, indices)
        case None => (tree, indices)
      }
  }

  /** Find the root where symbols should be searched for. Useful for handling Pats. */
  def findRealRoot(root: Tree) =
    getSpecifiedOrFindDefinition(Some(root)).flatMap {
      case (v @ (_: Pat.Var), name) =>
        (traverseTreeWithIndices _)
          .tupled(getIndicesFromPat(v))
          .map(foundedRoot => (foundedRoot, name))
      case foundedRoot => Some(foundedRoot)
    }
}
