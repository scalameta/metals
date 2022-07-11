package scala.meta.internal.metals

import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.SymbolOccurrence.Role
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.{semanticdb => s}

import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer

import scala.meta.io.AbsolutePath

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
import scala.meta.Ctor
import scala.meta.Name
import scala.meta.Type
import com.google.gson.JsonElement

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets
) extends SemanticdbFeatureProvider {

  override def reset(): Unit = ()

  override def onDelete(file: AbsolutePath): Unit = ()

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = ()

  case class CallHierarchyItemInfo(displayName: String, symbol: String)

  private def findDefinition(from: Tree): Option[Tree] = from match {
    case definition @ (_: Defn.Def | _: Defn.Val | _: Defn.Var |
        _: Ctor.Secondary) =>
      Some(definition)
    case name: Type.Name =>
      for {
        parent <- name.parent
        ctor <- parent.children.find(_.is[Ctor.Primary])
      } yield ctor
    case other =>
      other.parent.flatMap(p => findDefinition(p))
  }

  private def symbolOccurenceToCallHierarchyItem(
      source: AbsolutePath,
      doc: TextDocument,
      occurence: SymbolOccurrence,
      range: l.Range
  ): Option[CallHierarchyItem] = {
    val info = doc.symbols.find(_.symbol == occurence.symbol).get

    val chi = info.kind match {
      case SymbolInformation.Kind.METHOD =>
        Some(
          new CallHierarchyItem(
            info.displayName,
            SymbolKind.Method,
            source.toURI.toString,
            range,
            occurence.toLocation(source.toURI.toString).getRange
          )
        )
      case SymbolInformation.Kind.CONSTRUCTOR | SymbolInformation.Kind.CLASS =>
        Some(
          new CallHierarchyItem(
            info.displayName,
            SymbolKind.Constructor,
            source.toURI.toString,
            range,
            occurence.toLocation(source.toURI.toString).getRange
          )
        )
      case _ =>
        None
    }
    val symbol =
      if (info.isClass)
        info.signature
          .asInstanceOf[s.ClassSignature]
          .declarations
          .flatMap(_.symlinks.find(_.endsWith("`<init>`().")))
          .getOrElse(info.symbol)
      else info.symbol

    val displayName =
      if (info.isConstructor && info.displayName == "<init>")
        info.symbol.slice(
          info.symbol.lastIndexOf("/") + 1,
          info.symbol.lastIndexOf("#")
        )
      else info.displayName

    chi.foreach(item => {
      item.setData(CallHierarchyItemInfo(displayName, symbol))
      item.setName(displayName)
    })
    chi
  }

  def prepare(params: CallHierarchyPrepareParams): List[CallHierarchyItem] = {
    val source = params.getTextDocument.getUri.toAbsolutePath

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results: List[ResolvedSymbolOccurrence] =
          definition.positionOccurrences(source, params.getPosition, doc)
        results.flatMap { result =>
          for {
            occurence <- result.occurrence
            range <- occurence.range
            tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
            definition <- findDefinition(tree)
            chi <- symbolOccurenceToCallHierarchyItem(
              source,
              doc,
              result.occurrence.get,
              definition.pos.toLSP
            )
          } yield chi
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
  ): List[(SymbolOccurrence, l.Range, List[l.Range])] = {

    def search(
        tree: Tree,
        parent: Option[Name],
        parentRange: Option[l.Range]
    ): List[(SymbolOccurrence, l.Range, l.Range)] = tree match {
      case name: Name =>
        val occurences = definition
          .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
          .flatMap(_.occurrence.filter(_.role == Role.REFERENCE))

        parent match {
          case Some(parent)
              if occurences.exists(occ =>
                occ.symbol == info.symbol ||
                  (parent.is[Term.Apply] && info.symbol
                    .startsWith(occ.symbol.dropRight(1)))
              ) =>
            definition
              .positionOccurrences(source, parent.pos.toLSP.getStart, doc)
              .flatMap(_.occurrence)
              .map(occurence => (occurence, name.pos.toLSP, parentRange.get))
          case _ =>
            name.children.flatMap { child =>
              search(child, parent, parentRange)
            }
        }

      case method: Defn.Def =>
        method.children.flatMap { child =>
          search(child, Some(method.name), Some(method.pos.toLSP))
        }
      case constructor: Ctor.Secondary =>
        constructor.children.flatMap { child =>
          search(
            child,
            Some(constructor.name: Name),
            Some(constructor.pos.toLSP)
          )
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
          search(child, name, function.parent.map(_.pos.toLSP))
        }
      case other =>
        other.children.flatMap { child =>
          search(child, parent, parentRange)
        }
    }
    search(root, None, None)
      .groupBy(_._1)
      .map { case (k, v) => (k, v.head._3, v.map(_._2)) }
      .toList
  }

  def incomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): List[CallHierarchyIncomingCall] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results = trees
          .get(source)
          .map(root =>
            findIncomingCalls(
              source,
              doc,
              root,
              info
            )
          )
          .getOrElse(Nil)
        results.flatMap { case (occurence, range, ranges) =>
          symbolOccurenceToCallHierarchyItem(source, doc, occurence, range).map(
            chi =>
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

  private def findOutgoingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
      info: CallHierarchyItemInfo
  ) = {

    def search(tree: Tree): List[(SymbolOccurrence, l.Range, l.Range)] =
      tree match {
        case name: Name =>
          (for {
            (occurence, symInfo) <- definition
              .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
              .flatMap(_.occurrence.collect {
                case symOcc @ SymbolOccurrence(_, symbol, role)
                    if role == Role.REFERENCE =>
                  doc.symbols
                    .find(symInfo =>
                      symInfo.symbol == symbol && (symInfo.isConstructor || symInfo.isMethod)
                    )
                    .map(symInfo => (symOcc, symInfo))
              }.flatten)
              .headOption
            definitionOccurence <- doc.occurrences.find(occ =>
              occ.symbol == occurence.symbol && occ.role == Role.DEFINITION
            )
            definitionRange <- definitionOccurence.range
            definitionName <- trees.findLastEnclosingAt(
              source,
              definitionRange.toLSP.getStart
            )
            definition <- findDefinition(definitionName)
            if info.symbol != symInfo.symbol
          } yield (
            definitionOccurence,
            name.pos.toLSP,
            definition.pos.toLSP
          )).toList
        case other =>
          other.children.flatMap(search)
      }
    search(root)
      .groupBy(_._1)
      .map { case (k, v) => (k, v.head._3, v.map(_._2)) }
      .toList
  }

  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): List[CallHierarchyOutgoingCall] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results = trees
          .findLastEnclosingAt(source, params.getItem.getRange.getStart)
          .map(root =>
            findOutgoingCalls(
              source,
              doc,
              root,
              info
            )
          )
          .getOrElse(Nil)

        results.flatMap { case (occurence, range, ranges) =>
          symbolOccurenceToCallHierarchyItem(source, doc, occurence, range).map(
            chi =>
              new CallHierarchyOutgoingCall(
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
