package scala.meta.internal.metals

import scala.meta.internal.metals.JsonParser._
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

import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.{lsp4j => l}
import scala.meta.Tree
import scala.meta.Defn
import scala.meta.Pat
import scala.meta.Ctor
import scala.meta.Name
import com.google.gson.JsonElement
import scala.meta.Member
import scala.meta.Term
import scala.meta.Mod

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    icons: Icons,
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets
) extends SemanticdbFeatureProvider {

  override def reset(): Unit = ()

  override def onDelete(file: AbsolutePath): Unit = ()

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = ()

  case class CallHierarchyItemInfo(
      symbols: Array[String],
      visited: Array[String]
  )

  private def extractNameFromDefinition(tree: Tree): Option[(Tree, Name)] =
    tree match {
      case v: Defn.Val =>
        v.pats match {
          case (pat: Pat.Var) :: Nil => Some((v, pat.name))
          case _ => None
        }
      case v: Defn.Var =>
        v.pats match {
          case (pat: Pat.Var) :: Nil => Some((v, pat.name))
          case _ => None
        }
      case member: Defn with Member => Some((member, member.name))
      case ctor: Ctor => Some((ctor, ctor.name))
      case _ => None
    }

  private def findDefinition(from: Option[Tree]): Option[(Tree, Name)] =
    from
      .filterNot(_.is[Term.Param])
      .flatMap(tree =>
        extractNameFromDefinition(tree) match {
          case result @ Some(_) => result
          case None => findDefinition(tree.parent)
        }
      )

  private def symbolOccurenceToCallHierarchyItem(
      source: AbsolutePath,
      doc: TextDocument,
      occurence: SymbolOccurrence,
      range: l.Range,
      visited: Array[String]
  ): Option[CallHierarchyItem] =
    doc.symbols
      .find(_.symbol == occurence.symbol)
      .map(info => {
        val chi = new CallHierarchyItem(
          info.displayName,
          info.kind.toLSP,
          source.toURI.toString,
          range,
          occurence.toLocation(source.toURI.toString).getRange
        )
        val symbols = (Set(info.symbol) union (
          if (info.isClass)
            info.signature
              .asInstanceOf[s.ClassSignature]
              .declarations
              .flatMap(_.symlinks.find(_.endsWith("`<init>`().")))
              .toSet union (
              // For the case class, the symbols of the occurrences found are not _empty_/Example2# but _empty_/Example2.
              if (info.isCase) Set(info.symbol.replace("#", ".")) else Set.empty
            )
          else Set(info.symbol)
        )).toArray

        val displayName =
          if (info.isConstructor && info.displayName == "<init>")
            info.symbol.slice(
              info.symbol.lastIndexOf("/") + 1,
              info.symbol.lastIndexOf("#")
            )
          else info.displayName
        chi.setName(displayName)

        if (visited.dropRight(1).contains(occurence.symbol))
          chi.setDetail(s"${icons.sync}")

        chi.setData(CallHierarchyItemInfo(symbols, visited))
        chi
      })

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
            (definition, _) <- findDefinition(Some(tree))
            chi <- symbolOccurenceToCallHierarchyItem(
              source,
              doc,
              result.occurrence.get,
              definition.pos.toLSP,
              Array(occurence.symbol)
            )
          } yield chi
        }
      case None =>
        Nil
    }
  }

  private def containsDuplicates[T](visited: Seq[T]) =
    visited.view
      .scanLeft(Set.empty[T])((set, a) => set + a)
      .zip(visited.view)
      .exists { case (set, a) => set contains a }

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
      case name: Name
          if definition
            .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
            .flatMap(
              _.occurrence.filter(occ =>
                occ.role == Role.REFERENCE && info.symbols.contains(occ.symbol)
              )
            )
            .nonEmpty =>
        parent match {
          case Some(parent) =>
            definition
              .positionOccurrences(source, parent.pos.toLSP.getStart, doc)
              .flatMap(_.occurrence)
              .map(occurence => (occurence, name.pos.toLSP, parentRange.get))
          case _ =>
            name.children.flatMap { child =>
              search(child, parent, parentRange)
            }
        }
      case _ => {
        extractNameFromDefinition(tree) match {
          case Some((definition, name)) =>
            tree.children.flatMap(child =>
              search(child, Some(name), Some(definition.pos.toLSP))
            )
          case None =>
            tree.children.flatMap(child => search(child, parent, parentRange))
        }
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

    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    references
      .pathsWithReferences(
        params.getItem.getUri.toAbsolutePath,
        info.symbols.toSet
      )
      .toList
      .flatMap(source => {
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
            results.flatMap {
              case (occurence, range, ranges)
                  if !containsDuplicates(info.visited) =>
                symbolOccurenceToCallHierarchyItem(
                  source,
                  doc,
                  occurence,
                  range,
                  info.visited :+ occurence.symbol
                )
                  .map(chi =>
                    new CallHierarchyIncomingCall(
                      chi,
                      ranges.asJava
                    )
                  )
              case _ => None
            }
          case None =>
            Nil
        }
      })
  }

  private def findOutgoingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree
  ) = {
    def findDefinitionOccurence(
        symbol: String
    ): Option[(SymbolOccurrence, AbsolutePath, TextDocument)] = {
      references
        .pathsWithReferences(source, Set(symbol))
        .view
        .map(source =>
          for {
            doc <- semanticdbs.textDocument(source).documentIncludingStale
            occ <- doc.occurrences.find(occ =>
              occ.symbol == symbol && occ.role == Role.DEFINITION && doc.symbols
                .exists(symInfo => symInfo.symbol == symbol)
            )
          } yield (occ, source, doc)
        )
        .find(_.isDefined)
        .flatten
    }

    def search(
        tree: Tree
    ): List[(SymbolOccurrence, l.Range, l.Range, AbsolutePath, TextDocument)] =
      tree match {
        case name: Name =>
          (for {
            (definitionOccurence, definitionSource, definitionDoc) <- definition
              .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
              .flatMap(rso =>
                rso.occurrence.flatMap(occ =>
                  findDefinitionOccurence(occ.symbol)
                    // For the case class, the symbols of the occurrences found are not _empty_/Example2# but _empty_/Example2.
                    .orElse(
                      if (occ.symbol.endsWith(".") && !occ.symbol.contains("#"))
                        findDefinitionOccurence(occ.symbol.replace(".", "#"))
                      else
                        None
                    )
                )
              )
              .sortBy(_._1.symbol.length * -1)
              .headOption // The most specific occurrence is the longest
            definitionRange <- definitionOccurence.range
            definitionName <- trees.findLastEnclosingAt(
              definitionSource,
              definitionRange.toLSP.getStart
            )
            (definition, _) <- findDefinition(Some(definitionName))
          } yield (
            definitionOccurence,
            name.pos.toLSP,
            definition.pos.toLSP,
            definitionSource,
            definitionDoc
          )).toList
        case t
            if extractNameFromDefinition(t).isDefined || t.is[Term.Param] || t
              .is[Pat.Var] =>
          Nil
        case other =>
          other.children.flatMap(search)
      }

    (if (root.is[Mod.Case]) root.parent.get else root).children
      .filterNot(_.is[Name])
      .flatMap(search)
      .groupBy(_._1)
      .map { case (k, v) => (k, v.head._3, v.map(_._2), v.head._4, v.head._5) }
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
              root
            )
          )
          .getOrElse(Nil)

        results.flatMap {
          case (occurence, range, ranges, definitionSource, definitionDoc)
              if !containsDuplicates(info.visited) =>
            symbolOccurenceToCallHierarchyItem(
              definitionSource,
              definitionDoc,
              occurence,
              range,
              info.visited :+ occurence.symbol
            ).map(chi =>
              new CallHierarchyOutgoingCall(
                chi,
                ranges.asJava
              )
            )
          case _ => None
        }
      case None =>
        Nil
    }
  }
}
