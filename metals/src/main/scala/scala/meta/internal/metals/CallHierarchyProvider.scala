package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.SymbolOccurrence.Role
import scala.meta.internal.{semanticdb => s}

import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer

import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels

import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}
import scala.meta.Tree
import scala.meta.Defn
import scala.meta.Term
import scala.meta.Pat
import com.google.gson.JsonObject

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets
) extends SemanticdbFeatureProvider {

  private var referencedPackages: BloomFilter[CharSequence] =
    BloomFilters.create(10000)

  case class IndexEntry(
      id: BuildTargetIdentifier,
      bloom: BloomFilter[CharSequence]
  )
  val index: TrieMap[Path, IndexEntry] = TrieMap.empty

  override def reset(): Unit = {
    index.clear()
  }

  override def onDelete(file: AbsolutePath): Unit = {
    index.remove(file.toNIO)
  }

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    buildTargets.inverseSources(file).map { id =>
      val count = docs.documents.foldLeft(0)(_ + _.occurrences.length)
      val syntheticsCount = docs.documents.foldLeft(0)(_ + _.synthetics.length)
      val bloom = BloomFilter.create(
        Funnels.stringFunnel(StandardCharsets.UTF_8),
        Integer.valueOf((count + syntheticsCount) * 2),
        0.01
      )

      val entry = IndexEntry(id, bloom)
      index(file.toNIO) = entry
      docs.documents.foreach { d =>
        d.occurrences.foreach { o =>
          if (o.symbol.endsWith("/")) {
            referencedPackages.put(o.symbol)
          }
          bloom.put(o.symbol)
        }
        d.synthetics.foreach { synthetic =>
          Synthetics.foreachSymbol(synthetic) { sym =>
            bloom.put(sym)
            Synthetics.Continue
          }
        }
      }
    }
  }

  case class CallHierarchyItemInfo(displayName: String, symbol: String)

  private def symbolOccurenceToCallHierarchyItem(
      source: AbsolutePath,
      doc: TextDocument,
      occurence: SymbolOccurrence
  ): Option[CallHierarchyItem] = {
    val info = doc.symbols.find(_.symbol == occurence.symbol).get
    if (info.isMethod) {
      val range = occurence.toLocation(source.toURI.toString()).getRange()
      val chi = new CallHierarchyItem(
        info.displayName,
        SymbolKind.Method,
        source.toURI.toString,
        range,
        range
      )
      chi.setData(CallHierarchyItemInfo(info.displayName, info.symbol))
      Some(chi)
    } else None
  }

  def prepare(params: CallHierarchyPrepareParams): List[CallHierarchyItem] = {
    val source = params.getTextDocument.getUri.toAbsolutePath

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results: List[ResolvedSymbolOccurrence] =
          definition.positionOccurrences(source, params.getPosition, doc)
        results.flatMap { result =>
          symbolOccurenceToCallHierarchyItem(source, doc, result.occurrence.get)
        }
      case None =>
        Nil
    }
  }

  private def findIncomingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
      info: CallHierarchyItemInfo
  ): List[(SymbolOccurrence, List[l.Range])] = {
    def search(
        tree: Tree,
        parent: Option[Term.Name]
    ): List[(SymbolOccurrence, l.Range)] = tree match {
      case name: Term.Name
          if parent.isDefined && name.value == info.displayName && definition
            .positionOccurrences(source, name.pos.toLSP.getStart, doc)
            .flatMap(_.occurrence)
            .exists(occ =>
              occ.symbol == info.symbol && occ.role == Role.REFERENCE
            ) =>
        definition
          .positionOccurrences(source, parent.get.pos.toLSP.getStart, doc)
          .flatMap(_.occurrence)
          .map(occurence => (occurence, name.pos.toLSP))
      case method: Defn.Def =>
        method.children.flatMap { child =>
          search(child, Some(method.name))
        }
      case function: Term.Function =>
        function.children.flatMap { child =>
          val name = function.parent.flatMap {
            case v: Defn.Val =>
              v.pats match {
                case (pat: Pat.Var) :: Nil => Some(pat.name)
                case _ => None
              }
            case v: Defn.Var =>
              v.pats match {
                case (pat: Pat.Var) :: Nil => Some(pat.name)
                case _ => None
              }
            case _ => None
          }
          search(child, name)
        }
      case other =>
        other.children.flatMap { child =>
          search(child, parent)
        }
    }
    search(root, None)
      .groupBy(_._1)
      .map { case (k, v) => (k, v.map(_._2)) }
      .toList
  }

  def incomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): List[CallHierarchyIncomingCall] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = params.getItem.getData.asInstanceOf[JsonObject]

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results = trees
          .get(source)
          .map(root =>
            findIncomingCalls(
              source,
              doc,
              root,
              CallHierarchyItemInfo(
                info.get(("displayName")).getAsString(),
                info.get("symbol").getAsString()
              )
            )
          )
          .getOrElse(Nil)
        results.flatMap { case (occurence, ranges) =>
          symbolOccurenceToCallHierarchyItem(source, doc, occurence).map(chi =>
            new CallHierarchyIncomingCall(
              chi,
              ranges.asJava
            )
          )
        }
      case None =>
        Nil
    }
  }
}
