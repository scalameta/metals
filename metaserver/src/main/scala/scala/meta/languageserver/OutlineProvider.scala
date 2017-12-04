package scala.meta.languageserver

import scala.collection.mutable
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import langserver.{types => l}
import langserver.messages.DefinitionResult
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta._

object OutlineProvider extends LazyLogging {

  private class OutlineTraverser(path: AbsolutePath) {
    private val builder = List.newBuilder[l.SymbolInformation]

    val traverser = new Traverser {
      private var currentRoot: Option[Tree] = None
      override def apply(currentNode: Tree): Unit = {
        def continue(withNewRoot: Boolean = false): Unit = {
          val oldRoot = currentRoot
          if (withNewRoot) currentRoot = Some(currentNode)
          super.apply(currentNode)
          currentRoot = oldRoot
        }

        def addName(name: String): Unit = {
          builder += l.SymbolInformation(
            name = name,
            kind = currentNode.symbolKind,
            location = path.toLocation(currentNode.pos),
            containerName = currentRoot.flatMap(_.qualifiedName)
          )
        }

        def addNode(): Unit = currentNode.names.foreach(addName)

        currentNode match {
          // we need to go deeper
          case Source(_) | Template(_) => continue()
          // add package, but don't set it as a new root
          case Pkg(_) => addNode(); continue()
          // terminal nodes: add them, but don't go inside
          case Defn.Def(_) | Defn.Val(_) | Defn.Var(_) => addNode()
          case Decl.Def(_) | Decl.Val(_) | Decl.Var(_) => addNode()
          // all other (named) types and terms can contain more nodes
          case t if t.is[Member.Type] || t.is[Member.Term] =>
            addNode(); continue(withNewRoot = true)
          case _ => ()
        }
      }
    }

    def apply(tree: Tree): List[l.SymbolInformation] = {
      traverser.apply(tree)
      builder.result()
    }
  }

  def documentSymbols(
      path: AbsolutePath,
      source: Source
  ): List[l.SymbolInformation] =
    new OutlineTraverser(path).apply(source)
}
