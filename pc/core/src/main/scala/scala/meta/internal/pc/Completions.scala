package scala.meta.internal.pc

import scala.meta.internal.semanticdb.Scala._
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Utility methods for completions.
 */
trait Completions { this: MetalsGlobal =>

  /**
   * A member for symbols on the classpath that are not in scope, produced via workspace/symbol.
   */
  class WorkspaceMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

  val packageSymbols = mutable.Map.empty[String, Option[Symbol]]
  def packageSymbolFromString(symbol: String): Option[Symbol] = {
    packageSymbols.getOrElseUpdate(symbol, {
      val fqn = symbol.stripSuffix("/").replace('/', '.')
      try {
        Some(rootMirror.staticPackage(fqn))
      } catch {
        case NonFatal(_) =>
          None
      }
    })
  }

  /**
   * Returns a high number for less relevant symbols and low number for relevant numbers.
   *
   * Relevance is computed based on several factors such as
   * - local vs global
   * - public vs private
   * - synthetic vs non-synthetic
   */
  def relevancePenalty(
      m: Member,
      qual: Option[Type],
      history: ShortenedNames
  ): Int = m match {
    case TypeMember(sym, _, true, isInherited, _) =>
      computeRelevancePenalty(
        sym,
        m.implicitlyAdded,
        isInherited,
        qual,
        history
      )
    case w: WorkspaceMember =>
      MemberOrdering.IsWorkspaceSymbol + w.sym.name.length()
    case ScopeMember(sym, _, true, _) =>
      computeRelevancePenalty(
        sym,
        m.implicitlyAdded,
        isInherited = false,
        qual,
        history
      )
    case _ =>
      Int.MaxValue
  }

  /** Computes the relative relevance of a symbol in the completion list
   * This is an adaptation of
   * https://github.com/scala-ide/scala-ide/blob/a17ace0ee1be1875b8992664069d8ad26162eeee/org.scala-ide.sdt.core/src/org/scalaide/core/completion/ProposalRelevanceCalculator.scala
   */
  private def computeRelevancePenalty(
      sym: Symbol,
      viaImplicitConversion: Boolean,
      isInherited: Boolean,
      qual: Option[Type],
      history: ShortenedNames
  ): Int = {
    import MemberOrdering._
    var relevance = 0
    // local symbols are more relevant
    if (!sym.isLocalToBlock) relevance |= IsNotLocalByBlock
    // fields are more relevant than non fields
    if (!sym.hasGetter) relevance |= IsNotGetter
    // non-inherited members are more relevant
    if (isInherited) relevance |= IsInherited
    // symbols whose owner is a base class are less relevant
    val isInheritedBaseMethod = sym.owner match {
      case definitions.AnyClass | definitions.AnyRefClass |
          definitions.ObjectClass =>
        true
      case _ =>
        false
    }
    if (isInheritedBaseMethod)
      relevance |= IsInheritedBaseMethod
    // symbols not provided via an implicit are more relevant
    if (viaImplicitConversion) relevance |= IsImplicitConversion
    if (sym.hasPackageFlag) relevance |= IsPackage
    // accessors of case class members are more relevant
    if (!sym.isCaseAccessor) relevance |= IsNotCaseAccessor
    // public symbols are more relevant
    if (!sym.isPublic) relevance |= IsNotCaseAccessor
    // synthetic symbols are less relevant (e.g. `copy` on case classes)
    if (sym.isSynthetic) relevance |= IsSynthetic
    if (sym.isDeprecated) relevance |= IsDeprecated
    relevance
  }

  def memberOrdering(
      qual: Option[Type],
      history: ShortenedNames
  ): Ordering[Member] = new Ordering[Member] {
    override def compare(o1: Member, o2: Member): Int = {
      val byRelevance = Integer.compare(
        relevancePenalty(o1, qual, history),
        relevancePenalty(o2, qual, history)
      )
      if (byRelevance != 0) byRelevance
      else {
        val byIdentifier =
          IdentifierComparator.compare(o1.sym.name, o2.sym.name)
        if (byIdentifier != 0) byIdentifier
        else {
          val byOwner = o1.sym.owner.fullName.compareTo(o2.sym.owner.fullName)
          if (byOwner != 0) byOwner
          else {
            val byParamCount = Integer.compare(
              o1.sym.paramss.iterator.flatten.size,
              o2.sym.paramss.iterator.flatten.size
            )
            if (byParamCount != 0) byParamCount
            else {
              detailString(qual, o1, history)
                .compareTo(detailString(qual, o2, history))
            }
          }
        }
      }
    }
  }

  def infoString(sym: Symbol, info: Type, history: ShortenedNames): String =
    sym match {
      case m: MethodSymbol =>
        new SignaturePrinter(m, history, info, includeDocs = false).defaultMethodSignature
      case _ =>
        def fullName(s: Symbol): String = " " + s.owner.fullName
        dealiasedValForwarder(sym) match {
          case dealiased :: _ =>
            fullName(dealiased)
          case _ =>
            if (sym.isModuleOrModuleClass || sym.hasPackageFlag || sym.isClass) {
              fullName(sym)
            } else {
              val short = shortType(info, history)
              sym.infoString(short)
            }
        }
    }

  def detailString(
      qual: Option[Type],
      r: Member,
      history: ShortenedNames
  ): String = {
    qual match {
      case Some(tpe) if !r.sym.hasPackageFlag =>
        // Compute type parameters based on the qualifier.
        // Example: Map[Int, String].applyOrE@@
        // Before: getOrElse[V1 >: V]     (key: K,   default: => V1): V1
        // After:  getOrElse[V1 >: String](key: Int, default: => V1): V1
        infoString(r.sym, tpe.memberType(r.sym), history)
      case _ =>
        if (r.sym.hasRawInfo) {
          infoString(r.sym, r.sym.rawInfo, history)
        } else {
          "<_>"
        }
    }
  }

  def dealiasedValForwarder(sym: Symbol): List[Symbol] = {
    if (sym.isValue && sym.hasRawInfo && !semanticdbSymbol(sym).isLocal) {
      sym.rawInfo match {
        case SingleType(_, dealias) if dealias.isModule =>
          dealias :: dealias.companion :: Nil
        case _ =>
          Nil
      }
    } else {
      Nil
    }
  }
}
