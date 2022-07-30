package scala.meta.internal.metals

import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
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
import scala.meta.Name
import com.google.gson.JsonElement
import scala.meta.Member
import scala.meta.Term
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.Decl
import scala.meta.Type
import scala.meta.Init
import scala.meta.Template

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    icons: Icons,
    compilers: () => Compilers,
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets,
) extends SemanticdbFeatureProvider {

  import CanBuildCallHierarchyItem._

  override def reset(): Unit = ()

  override def onDelete(file: AbsolutePath): Unit = ()

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = ()

  private implicit val symbolOccurence2CanBuildCallHierarchyItem =
    new CanBuildCallHierarchyItemBuilder[SymbolOccurrence] {
      def apply(
          occurence: SymbolOccurrence
      ): CanBuildCallHierarchyItem[SymbolOccurrence] = SymbolOccurenceCHI(
        occurence
      )
    }

  private implicit val selectTree2CanBuildCallHierarchyItem =
    new CanBuildCallHierarchyItemBuilder[s.SelectTree] {
      def apply(
          selectTree: s.SelectTree
      ): CanBuildCallHierarchyItem[s.SelectTree] = SelectTreeCHI(selectTree)
    }

  case class CallHierarchyItemInfo(
      symbols: Array[String],
      visited: Array[String],
  )

  private def extractNameFromDefinitionPats[V <: Tree { def pats: List[Pat] }](
      v: V
  ) =
    v.pats match {
      case (pat: Pat.Var) :: Nil => Some((v, pat.name))
      case _ => None
    }

  private def extractNameFromDefinition(tree: Tree): Option[(Tree, Name)] =
    tree match {
      case v: Defn.Val => extractNameFromDefinitionPats(v)
      case v: Defn.Var => extractNameFromDefinitionPats(v)
      case v: Decl.Val => extractNameFromDefinitionPats(v)
      case v: Decl.Var => extractNameFromDefinitionPats(v)
      case member: Member => Some((member, member.name))
      case _ => None
    }

  private def isTypeDeclaration(tree: Tree): Boolean =
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

  private def findDefinition(from: Option[Tree]): Option[(Tree, Name)] =
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

  private def buildCallHierarchyItemFrom(
      source: AbsolutePath,
      doc: TextDocument,
      item: CanBuildCallHierarchyItem[_],
      range: l.Range,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Option[CallHierarchyItem]] = {
    item match {
      case CanBuildCallHierarchyItem(symbol, itemRange) =>
        compilers()
          .hover(
            new HoverExtParams(
              source.toTextDocumentIdentifier,
              null,
              itemRange,
            ),
            token,
          )
          .map(hover =>
            doc.symbols
              .find(_.symbol == symbol)
              .map(info => {

                val chi = new CallHierarchyItem(
                  info.displayName,
                  info.kind.toLSP,
                  source.toURI.toString,
                  range,
                  itemRange,
                )
                val symbols = (Set(info.symbol) union (
                  if (info.isClass)
                    info.signature
                      .asInstanceOf[s.ClassSignature]
                      .declarations
                      .flatMap(_.symlinks.find(_.endsWith("`<init>`().")))
                      .toSet
                  else Set.empty
                )).toArray

                val displayName =
                  if (info.isConstructor && info.displayName == "<init>")
                    info.symbol.slice(
                      info.symbol.lastIndexOf("/") + 1,
                      info.symbol.lastIndexOf("#"),
                    )
                  else info.displayName
                chi.setName(displayName)

                chi.setDetail(
                  (if (visited.dropRight(1).contains(symbol))
                     icons.sync + " "
                   else "") + getSignatureFromHover(hover).getOrElse("")
                )

                chi.setData(CallHierarchyItemInfo(symbols, visited))
                chi
              })
          )
      case _ => Future.successful(None)
    }
  }

  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results: List[ResolvedSymbolOccurrence] =
          definition.positionOccurrences(source, params.getPosition, doc)
        Future
          .sequence(results.flatMap { result =>
            for {
              occurence <- result.occurrence
              if occurence.role.isDefinition
              range <- occurence.range
              tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
              (definition, _) <- findDefinition(Some(tree))
              chi = buildCallHierarchyItemFrom(
                source,
                doc,
                SymbolOccurenceCHI(occurence),
                definition.pos.toLSP,
                Array(occurence.symbol),
                token,
              )
            } yield chi
          })
          .map(_.flatten)
      case None =>
        Future.successful(Nil)
    }
  }

  private def containsDuplicates[T](visited: Seq[T]) =
    visited.view
      .scanLeft(Set.empty[T])((set, a) => set + a)
      .zip(visited.view)
      .exists { case (set, a) => set contains a }

  private def groupIncomingCalls[A](calls: Seq[(A, l.Range, l.Range)])(implicit
      builder: CanBuildCallHierarchyItemBuilder[A]
  ): List[(CanBuildCallHierarchyItem[A], l.Range, Seq[l.Range])] =
    calls
      .groupBy(_._1)
      .map { case (k, v) => (builder(k), v.head._3, v.map(_._2)) }
      .toList

  private def findIncomingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
      info: CallHierarchyItemInfo,
  ): List[
    (CanBuildCallHierarchyItem[SymbolOccurrence], l.Range, Seq[l.Range])
  ] = {
    def search(
        tree: Tree,
        parent: Option[Name],
        parentRange: Option[l.Range],
    ): List[(SymbolOccurrence, l.Range, l.Range)] = tree match {
      case name: Name
          if !isTypeDeclaration(name) && definition
            .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
            .flatMap(
              _.occurrence.filter(occ =>
                occ.role.isReference && info.symbols.contains(occ.symbol)
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

    groupIncomingCalls(search(root, None, None))
  }

  private def extractSelectTree(tree: s.Tree) =
    tree match {
      case selectTree: s.SelectTree => Some(selectTree)
      case s.TypeApplyTree(selectTree: s.SelectTree, _) => Some(selectTree)
      case _ => None
    }

  private def findIncomingCallsSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      info: CallHierarchyItemInfo,
  ): List[(CanBuildCallHierarchyItem[s.SelectTree], l.Range, Seq[l.Range])] = {
    groupIncomingCalls(
      doc.synthetics
        .flatMap(syn =>
          extractSelectTree(syn.tree).collect {
            case st @ s.SelectTree(s.OriginalTree(range), id)
                if id.exists(id => info.symbols.contains(id.symbol)) => (
              for {
                range <- range
                tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
                (definition, name) <- findDefinition(Some(tree))
              } yield (st, name.pos.toLSP, definition.pos.toLSP)
            )
          }
        )
        .flatten
    )
  }

  def incomingCalls(
      params: CallHierarchyIncomingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyIncomingCall]] = {
    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    Future
      .sequence(
        references
          .pathsMightContainSymbol(
            params.getItem.getUri.toAbsolutePath,
            info.symbols.toSet,
          )
          .toList
          .map(source => {
            semanticdbs.textDocument(source).documentIncludingStale match {
              case Some(doc) =>
                val results = trees
                  .get(source)
                  .map(root =>
                    findIncomingCalls(
                      source,
                      doc,
                      root,
                      info,
                    ) ++ findIncomingCallsSynthetics(
                      source,
                      doc,
                      info,
                    )
                  )
                  .getOrElse(Nil)

                Future
                  .sequence(results.map {
                    case (
                          item @ CanBuildCallHierarchyItem(symbol, _),
                          range,
                          ranges,
                        ) if !containsDuplicates(info.visited) =>
                      buildCallHierarchyItemFrom(
                        source,
                        doc,
                        item,
                        range,
                        info.visited :+ symbol,
                        token,
                      )
                        .mapOptionInside(chi =>
                          new CallHierarchyIncomingCall(
                            chi,
                            ranges.asJava,
                          )
                        )
                    case _ => Future.successful(None)
                  })
                  .map(_.flatten)
              case None =>
                Future.successful(Nil)
            }
          })
      )
      .map(_.flatten)
  }

  private def findDefinitionOccurence(
      source: AbsolutePath,
      symbol: String,
  ): Option[(SymbolOccurrence, AbsolutePath, TextDocument)] =
    references
      .pathsMightContainSymbol(source, Set(symbol))
      .view
      .map(source =>
        for {
          doc <- semanticdbs.textDocument(source).documentIncludingStale
          occ <- doc.occurrences.find(occ =>
            occ.symbol == symbol && occ.role.isDefinition && doc.symbols
              .exists(symInfo => symInfo.symbol == symbol)
          )
        } yield (occ, source, doc)
      )
      .find(_.isDefined)
      .flatten

  private def groupOutgoingCalls[A](
      calls: Seq[(A, l.Range, l.Range, AbsolutePath, TextDocument)]
  )(implicit builder: CanBuildCallHierarchyItemBuilder[A]): List[
    (
        CanBuildCallHierarchyItem[A],
        l.Range,
        List[l.Range],
        AbsolutePath,
        TextDocument,
    )
  ] =
    calls
      .groupBy(_._1)
      .map { case (k, v) =>
        (builder(k), v.head._3, v.map(_._2).toList, v.head._4, v.head._5)
      }
      .toList

  private def findOutgoingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  ): List[
    (
        CanBuildCallHierarchyItem[SymbolOccurrence],
        l.Range,
        List[l.Range],
        AbsolutePath,
        TextDocument,
    )
  ] = {

    val realRoot = findDefinition(Some(root))

    def search(
        tree: Tree
    ): List[(SymbolOccurrence, l.Range, l.Range, AbsolutePath, TextDocument)] =
      tree match {
        case name: Name if !isTypeDeclaration(name) =>
          (for {
            (definitionOccurence, definitionSource, definitionDoc) <- definition
              .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
              .flatMap(rso =>
                rso.occurrence.flatMap(occ =>
                  findDefinitionOccurence(source, occ.symbol)
                )
              )
              .sortBy(_._1.symbol.length * -1)
              .headOption // The most specific occurrence is the longest
            definitionRange <- definitionOccurence.range
            if !(definitionDoc == doc && realRoot.exists(
              _._1.pos.encloses(definitionRange.toLSP)
            ))
            definitionName <- trees.findLastEnclosingAt(
              definitionSource,
              definitionRange.toLSP.getStart,
            )
            (definition, _) <- findDefinition(Some(definitionName))
          } yield (
            definitionOccurence,
            name.pos.toLSP,
            definition.pos.toLSP,
            definitionSource,
            definitionDoc,
          )).toList
        case t
            if extractNameFromDefinition(t).isDefined || t.is[Term.Param] || t
              .is[Pat.Var] =>
          Nil
        case other =>
          other.children.flatMap(search)
      }

    realRoot match {
      case Some((definition, _)) =>
        groupOutgoingCalls[SymbolOccurrence](
          definition.children
            .filterNot(_.is[Name])
            .flatMap(search)
        )
      case None => Nil
    }
  }

  private def findOutgoingCallsSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  ): List[
    (
        CanBuildCallHierarchyItem[SymbolOccurrence],
        l.Range,
        List[l.Range],
        AbsolutePath,
        TextDocument,
    )
  ] =
    groupOutgoingCalls(
      (findDefinition(Some(root)) match {
        case Some((definition, _)) =>
          val defintionRange = definition.pos.toSemanticdb
          doc.synthetics.flatMap(syn =>
            extractSelectTree(syn.tree).collect {
              case s.SelectTree(
                    s.OriginalTree(Some(range)),
                    Some(s.IdTree(symbol)),
                  )
                  if defintionRange.encloses(range) && findDefinition(
                    trees.findLastEnclosingAt(source, range.toLSP.getStart)
                  ).exists(_._1 == definition) =>
                lazy val mightBeCaseClassConstructor = """\.apply\(\)\.$""".r
                findDefinitionOccurence(source, symbol)
                  .orElse(
                    if (
                      mightBeCaseClassConstructor.findFirstIn(symbol).isDefined
                    )
                      findDefinitionOccurence(
                        source,
                        mightBeCaseClassConstructor
                          .replaceAllIn(symbol, "#`<init>`()."),
                      )
                    else None
                  ) // for case class constructor
                  .flatMap {
                    case (
                          definitionOccurence,
                          definitionSource,
                          definitionDoc,
                        ) =>
                      for {
                        definitionRange <- definitionOccurence.range
                        if !(definitionDoc == doc && definition.pos
                          .encloses(definitionRange.toLSP))
                        definitionName <- trees.findLastEnclosingAt(
                          definitionSource,
                          definitionRange.toLSP.getStart,
                        )
                        (definition, _) <- findDefinition(Some(definitionName))
                      } yield (
                        definitionOccurence,
                        range.toLSP,
                        definition.pos.toLSP,
                        definitionSource,
                        definitionDoc,
                      )
                  }
            }
          )
        case None => Nil
      }).flatten
    )

  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyOutgoingCall]] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results = trees
          .findLastEnclosingAt(
            source,
            params.getItem.getSelectionRange.getStart,
          )
          .map(root =>
            findOutgoingCalls(
              source,
              doc,
              root,
            ) ++ findOutgoingCallsSynthetics(
              source,
              doc,
              root,
            )
          )
          .getOrElse(Nil)

        Future
          .sequence(results.map {
            case (
                  item @ CanBuildCallHierarchyItem(symbol, _),
                  range,
                  ranges,
                  definitionSource,
                  definitionDoc,
                ) if !containsDuplicates(info.visited) =>
              buildCallHierarchyItemFrom(
                definitionSource,
                definitionDoc,
                item,
                range,
                info.visited :+ symbol,
                token,
              ).mapOptionInside(chi =>
                new CallHierarchyOutgoingCall(
                  chi,
                  ranges.asJava,
                )
              )
            case _ => Future.successful(None)
          })
          .map(_.flatten)
      case None =>
        Future.successful(Nil)
    }
  }
}

object CanBuildCallHierarchyItem {
  trait CanBuildCallHierarchyItem[A] {
    def symbol: Option[String]
    def range: Option[l.Range]
  }

  trait CanBuildCallHierarchyItemBuilder[A] {
    def apply(item: A): CanBuildCallHierarchyItem[A]
  }

  case class SymbolOccurenceCHI(occurence: SymbolOccurrence)
      extends CanBuildCallHierarchyItem[SymbolOccurrence] {
    lazy val symbol = Some(occurence.symbol)
    lazy val range = occurence.range.map(_.toLSP)
  }

  case class SelectTreeCHI(selectTree: s.SelectTree)
      extends CanBuildCallHierarchyItem[s.SelectTree] {
    lazy val symbol = selectTree.id.map(_.symbol)
    lazy val range = selectTree match {
      case s.SelectTree(s.OriginalTree(range), _) => range.map(_.toLSP)
      case _ => None
    }
  }

  def unapply(item: CanBuildCallHierarchyItem[_]): Option[(String, l.Range)] =
    (for {
      symbol <- item.symbol
      range <- item.range
    } yield (symbol, range))
}
