package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Init
import scala.meta.Member
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Type
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

/** Utility functions for call hierarchy requests. */
private[callHierarchy] trait CallHierarchyHelpers {
  def extractNameFromMember(tree: Tree): Option[NamedRealRoot] =
    tree match {
      case member: Member => Some(NamedRealRoot(member, member.name))
      case _ => None
    }

  /** Type declarations are not considered in call hierarchy request, this function helps to filter them. */
  def isTypeDeclaration(tree: Tree): Boolean =
    tree.parent
      .fold(false) {
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
      }

  /**
   * Go up in the tree to find a specified tree or find any definition.
   * If the specified item is founded this function will return the `UnamedRealRoot(specified)`,
   * otherwise this function will return the definition in the form of `NamedRealRoot(definition, defintionName)`.
   */
  def getSpecifiedOrFindDefinition(
      from: Option[Tree],
      specified: Option[Tree] = None,
      prev: Option[Tree] = None,
      indices: List[Int] = Nil,
  ): Option[RealRoot] = {
    def findDefinitionFromTermWithArgs(term: Term, args: List[Term]) =
      prev.flatMap(prev =>
        getSpecifiedOrFindDefinition(
          term.parent,
          specified,
          Some(term),
          args.indexOf(prev) :: indices,
        )
      )

    /** Handle the cases of complex pats */
    def advancedFindDefinition(tree: Tree) = tree match {
      case v: Defn.Val =>
        v.pats.headOption.flatMap(pat =>
          traverseTreeWithIndices(pat, indices)
            .collect { case pat: Pat.Var =>
              NamedRealRoot(v, pat.name)
            }
        )
      case tuple: Term.Tuple =>
        findDefinitionFromTermWithArgs(tuple, tuple.args)

      case apply: Term.Apply =>
        findDefinitionFromTermWithArgs(apply, apply.args)
      case _ =>
        getSpecifiedOrFindDefinition(
          tree.parent,
          specified,
          Some(tree),
        )
    }

    specified
      .collect { case tree if from.contains(tree) => UnamedRealRoot(tree) }
      .orElse(
        from
          .filterNot(tree => tree.is[Term.Param] || isTypeDeclaration(tree))
          .flatMap(tree =>
            extractNameFromMember(tree) match {
              case result @ Some(_) =>
                result // We can consider a definition like an item from which we can extract its direct name
              case None =>
                advancedFindDefinition(tree)
            }
          )
      )
  }

  def containsDuplicates[T](visited: Seq[T]): Boolean =
    visited.view
      .scanLeft(Set.empty[T])((set, a) => set + a)
      .zip(visited.view)
      .exists { case (set, a) => set contains a }

  def extractSelectTree(tree: s.Tree): Option[s.SelectTree] =
    tree match {
      case selectTree: s.SelectTree => Some(selectTree)
      case s.TypeApplyTree(selectTree: s.SelectTree, _) => Some(selectTree)
      case _ => None
    }

  def traverseTreeWithIndices(tree: Tree, indices: List[Int]): Option[Tree] = {
    indices match {
      case i :: tail =>
        val traverseTreeWithArgs = (args: List[Tree]) =>
          args.lift(i).flatMap(arg => traverseTreeWithIndices(arg, tail))
        tree match {
          case tuple: Term.Tuple =>
            traverseTreeWithArgs(tuple.args)
          case apply: Term.Apply =>
            traverseTreeWithArgs(apply.args)
          case tuple: Pat.Tuple =>
            traverseTreeWithArgs(tuple.args)
          case extract: Pat.Extract =>
            traverseTreeWithArgs(extract.args)
          case _ => None
        }
      case Nil => Some(tree)
    }
  }

  private def getIndicesFromPat(
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
  def findRealRoot(root: Tree): Option[RealRoot] =
    getSpecifiedOrFindDefinition(Some(root)).flatMap {
      case NamedRealRoot(v @ (_: Pat.Var), name) =>
        (traverseTreeWithIndices _)
          .tupled(getIndicesFromPat(v))
          .map(foundedRoot => NamedRealRoot(foundedRoot, name))
      case foundedRoot => Some(foundedRoot)
    }

  /** Give the potentials paths where a set of symbols can be referenced */
  def pathsToCheck(
      references: ReferenceProvider,
      source: AbsolutePath,
      symbols: Set[String],
      isLocal: Boolean,
      searchLocal: Boolean = true,
  )(implicit ec: ExecutionContext): Future[List[AbsolutePath]] = {
    val futurePaths =
      if (!isLocal)
        references.allPathsFor(source, symbols)
      else Future.successful(Set.empty[AbsolutePath])
    futurePaths.map(paths => (paths ++ Option.when(searchLocal)(source)).toList)
  }
}
