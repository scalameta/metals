package scala.meta.languageserver.providers

import scala.meta._
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.Uri
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.DocumentSymbolResult
import langserver.{types => l}
import org.langmeta.io.AbsolutePath

object DocumentSymbolProvider extends LazyLogging {

  private class SymbolTraverser(uri: Uri) {
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
            location = l.Location(uri.value, currentNode.pos.toRange),
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

  def empty = DocumentSymbolResult(Nil)
  def documentSymbols(
      uri: Uri,
      source: Source
  ): DocumentSymbolResult =
    DocumentSymbolResult(new SymbolTraverser(uri).apply(source))
}
