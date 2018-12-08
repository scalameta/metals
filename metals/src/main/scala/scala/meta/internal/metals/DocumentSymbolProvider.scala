package scala.meta.internal.metals

import scala.meta._
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import MetalsEnrichments._

object LegacyMetalsEnrichments {

  /** All names within the node.
    * - if it's a package, it will have its qualified name: `package foo.bar.buh`
    * - if it's a val/var, it may contain several names in the pattern: `val (x, y, z) = ...`
    * - for everything else it's just its normal name (if it has one)
    */
  private def patternNames(pats: List[Pat]): Seq[String] =
    pats.flatMap { _.collect { case Pat.Var(name) => name.value } }

  /** Fully qualified name for packages, normal name for everything else */
  private def qualifiedName(tree: Tree): Option[String] = tree match {
    case Term.Name(name) => Some(name)
    case Term.Select(qual, name) =>
      qualifiedName(qual).map { prefix =>
        s"${prefix}.${name}"
      }
    case Pkg(sel: Term.Select, _) => qualifiedName(sel)
    case m: Member => Some(m.name.value)
    case _ => None
  }

  def names(tree: Tree): Seq[String] = tree match {
    case t: Pkg => qualifiedName(t).toSeq
    case t: Defn.Val => patternNames(t.pats)
    case t: Decl.Val => patternNames(t.pats)
    case t: Defn.Var => patternNames(t.pats)
    case t: Decl.Var => patternNames(t.pats)
    case t: Member => Seq(t.name.value)
    case _ => Seq()
  }

}

object DocumentSymbolProvider {

  private class SymbolTraverser() {
    private val builder = List.newBuilder[DocumentSymbol]

    val traverser = new Traverser {
      var currentRoot: Option[Tree] = None
      override def apply(currentNode: Tree): Unit = {
        def continue(withNewRoot: Boolean = false): Unit = {
          val oldRoot = currentRoot
          if (withNewRoot) currentRoot = Some(currentNode)
          super.apply(currentNode)
          currentRoot = oldRoot
        }

        def addName(name: String): Unit = {
          builder += new DocumentSymbol(
            name,
            symbolKind(currentNode),
            currentNode.pos.toLSP,
            currentNode.pos.toLSP
          )
        }

        def addNode(): Unit =
          LegacyMetalsEnrichments.names(currentNode).foreach(addName)

        currentNode match {
          // we need to go deeper
          case _: Source | _: Template => continue()
          // add package, but don't set it as a new root
          case _: Pkg => addNode(); continue()
          // terminal nodes: add them, but don't go inside
          case _: Defn.Def | _: Defn.Val | _: Defn.Var => addNode()
          case _: Decl.Def | _: Decl.Val | _: Decl.Var => addNode()
          // all other (named) types and terms can contain more nodes
          case t if t.is[Member.Type] || t.is[Member.Term] =>
            addNode(); continue(withNewRoot = true)
          case _ => ()
        }
      }
    }

    def apply(tree: Tree): List[DocumentSymbol] = {
      traverser.apply(tree)
      builder.result()
    }
  }

  def empty: List[DocumentSymbol] = Nil

  def documentSymbols(
      source: Source
  ): List[DocumentSymbol] =
    new SymbolTraverser().apply(source)

    // TODO(alexey) function inside a block/if/for/etc.?
  def isFunction(tree: Tree): Boolean = {
    val tpeOpt: Option[Type] = tree match {
      case d: Decl.Val => Some(d.decltpe)
      case d: Decl.Var => Some(d.decltpe)
      case d: Defn.Val => d.decltpe
      case d: Defn.Var => d.decltpe
      case _ => None
    }
    tpeOpt.filter(_.is[Type.Function]).nonEmpty
  }

    // NOTE: we care only about descendants of Decl, Defn and Pkg[.Object] (see documentSymbols implementation)
    def symbolKind(tree: Tree): SymbolKind = tree match {
      case f if isFunction(f) => SymbolKind.Function
      case _: Decl.Var | _: Defn.Var => SymbolKind.Variable
      case _: Decl.Val | _: Defn.Val => SymbolKind.Constant
      case _: Decl.Def | _: Defn.Def => SymbolKind.Method
      case _: Decl.Type | _: Defn.Type => SymbolKind.Field
      case _: Defn.Macro => SymbolKind.Constructor
      case _: Defn.Class => SymbolKind.Class
      case _: Defn.Trait => SymbolKind.Interface
      case _: Defn.Object => SymbolKind.Module
      case _: Pkg.Object => SymbolKind.Namespace
      case _: Pkg => SymbolKind.Package
      case _: Type.Param => SymbolKind.TypeParameter
      case _: Lit.Null => SymbolKind.Null
      // TODO(alexey) are these kinds useful?
      // case ??? => SymbolKind.Enum
      // case ??? => SymbolKind.String
      // case ??? => SymbolKind.Number
      // case ??? => SymbolKind.Boolean
      // case ??? => SymbolKind.Array
      case _ => SymbolKind.Field
    }
}
