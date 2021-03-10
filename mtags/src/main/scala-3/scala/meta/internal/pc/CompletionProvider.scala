package scala.meta.internal.pc

import scala.collection.mutable
import scala.meta.internal.pc.IdentifierComparator

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.SrcPos
import dotty.tools.dotc.util.{NameTransformer, NoSourcePosition, SourcePosition}

class CompletionProvider(
    pos: SourcePosition,
    ctx: Context
) {
  implicit val context: Context = ctx

  def completions(): List[Completion] = {
    val (offset, completions) = Completion.completions(pos)
    val query = String(pos.source.content.slice(offset, pos.endPos.point))
    completions.filterInteresting.sorted(completionOrdering(pos, query))
  }

  extension (c: Completion) {
    // completionItem method either way takes only head symbol, so why can safely ignore the rest
    def sym: Symbol = c.symbols.head
  }

  extension (s: SrcPos) {
    def isAfter(s1: SrcPos) =
      s.sourcePos.exists && s1.sourcePos.exists && s.sourcePos.point > s1.sourcePos.point
  }

  extension (l: List[Completion]) {
    def filterInteresting: List[Completion] = {
      val isSeen = mutable.Set.empty[String]
      val buf = List.newBuilder[Completion]
      def isInteresting(head: Completion): Boolean = {
        val id = head.sym.show
        def isNotLocalForwardReference: Boolean =
          !head.sym.isLocalToBlock ||
            !head.sym.srcPos.isAfter(pos) ||
            head.sym.is(Param)

        if (
          !isSeen(id) &&
          !isUninterestingSymbol(head.sym) &&
          isNotLocalForwardReference
        ) {
          isSeen += id
          buf += head
          true
        } else {
          false
        }
      }
      l.filter(isInteresting)
    }
  }

  private lazy val isUninterestingSymbol: Set[Symbol] = Set[Symbol](
    defn.Any_==,
    defn.Any_!=,
    defn.Any_##,
    defn.Object_eq,
    defn.Object_ne,
    defn.RepeatedParamClass,
    defn.ByNameParamClass2x,
    defn.Object_notify,
    defn.Object_notifyAll,
    defn.Object_notify,
    defn.ObjectClass.info.member(nme.wait_).symbol,
    // NOTE(olafur) IntelliJ does not complete the root package and without this filter
    // then `_root_` would appear as a completion result in the code `foobar(_<COMPLETE>)`
    defn.RootPackage,
    // NOTE(gabro) valueOf was added as a Predef member in 2.13. We filter it out since is a niche
    // use case and it would appear upon typing 'val'
    defn.ValueOfClass.info.member(nme.valueOf).symbol
  ).flatMap(_.alternatives.map(_.symbol)).toSet

  private def computeRelevancePenalty(
      sym: Symbol
  ): Int = {
    import MemberOrdering._
    var relevance = 0
    // local symbols are more relevant
    if (!sym.isLocalToBlock) relevance |= IsNotLocalByBlock
    // symbols defined in this file are more relevant
    if (pos.source != sym.source || sym.is(Package))
      relevance |= IsNotDefinedInFile
    // fields are more relevant than non fields
    if (sym.isField) relevance |= IsNotGetter
    // symbols whose owner is a base class are less relevant
    if (sym.owner == defn.AnyClass || sym.owner == defn.ObjectClass)
      relevance |= IsInheritedBaseMethod
    // symbols not provided via an implicit are more relevant
    if (sym.is(Implicit)) relevance |= IsImplicitConversion
    if (sym.is(Package)) relevance |= IsPackage
    // accessors of case class members are more relevant
    if (!sym.is(CaseAccessor)) relevance |= IsNotCaseAccessor
    // public symbols are more relevant
    if (!sym.isPublic) relevance |= IsNotCaseAccessor
    // synthetic symbols are less relevant (e.g. `copy` on case classes)
    if (sym.is(Synthetic)) relevance |= IsSynthetic
    if (sym.isDeprecated) relevance |= IsDeprecated
    if (isEvilMethod(sym.name)) relevance |= IsEvilMethod
    relevance
  }

  private lazy val isEvilMethod: Set[Name] = Set[Name](
    nme.notifyAll_,
    nme.notify_,
    nme.wait_,
    nme.clone_,
    nme.finalize_
  )

  private def completionOrdering(
      position: SourcePosition,
      query: String
  ): Ordering[Completion] = new Ordering[Completion] {
    val queryLower = query.toLowerCase()
    val fuzzyCache = mutable.Map.empty[Symbol, Int]
    def compareLocalSymbols(s1: Symbol, s2: Symbol): Int = {
      if (s1.isLocal && s2.isLocal) {
        if (s1.srcPos.isAfter(s2.srcPos)) -1
        else 1
      } else {
        0
      }
    }
    def fuzzyScore(o: Symbol): Int = {
      fuzzyCache.getOrElseUpdate(
        o, {
          val name = o.name.toString().toLowerCase()
          if (name.startsWith(queryLower)) 0
          else if (name.toLowerCase().contains(queryLower)) 1
          else 2
        }
      )
    }
    override def compare(o1: Completion, o2: Completion): Int = {
      val s1 = o1.sym
      val s2 = o2.sym
      val byLocalSymbol = compareLocalSymbols(s1, s2)
      if (byLocalSymbol != 0) byLocalSymbol
      else {
        val byRelevance = Integer.compare(
          computeRelevancePenalty(s1),
          computeRelevancePenalty(s2)
        )
        if (byRelevance != 0) byRelevance
        else {
          val byFuzzy = Integer.compare(
            fuzzyScore(s1),
            fuzzyScore(s2)
          )
          if (byFuzzy != 0) byFuzzy
          else {
            val byIdentifier = IdentifierComparator.compare(
              s1.name.toString,
              s2.name.toString
            )
            if (byIdentifier != 0) byIdentifier
            else {
              val byOwner =
                s1.owner.fullName.toString.compareTo(s2.owner.fullName.toString)
              if (byOwner != 0) byOwner
              else {
                val byParamCount = Integer.compare(
                  s1.typeParams.size,
                  s2.typeParams.size
                )
                if (byParamCount != 0) byParamCount
                else {
                  s1.show.compareTo(s2.show)
                }
              }
            }
          }
        }
      }
    }
  }
}
