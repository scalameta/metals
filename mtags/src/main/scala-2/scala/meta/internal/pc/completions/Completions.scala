package scala.meta.internal.pc.completions

import java.net.URI
import java.util.logging.Level

import scala.collection.immutable.Nil
import scala.collection.mutable
import scala.reflect.internal.Chars
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.IdentifierComparator
import scala.meta.internal.pc.InterpolationSplice
import scala.meta.internal.pc.MemberOrdering
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

/**
 * Utility methods for completions.
 */
trait Completions { this: MetalsGlobal =>

  val clientSupportsSnippets: Boolean =
    metalsConfig.isCompletionSnippetsEnabled()
  val coursierComplete = new CoursierComplete(BuildInfo.scalaCompilerVersion)

  /**
   * A member for symbols on the classpath that are not in scope, produced via workspace/symbol.
   */
  class WorkspaceMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree) {
    def additionalTextEdits: List[l.TextEdit] = Nil

    def wrap: String => String = identity

    def editRange: Option[l.Range] = None
  }

  class WorkspaceImplicitMember(sym: Symbol, val definingClassSymbol: Symbol)
      extends ScopeMember(sym, sym.tpe, true, EmptyTree)

  class WorkspaceInterpolationMember(
      sym: Symbol,
      override val additionalTextEdits: List[l.TextEdit],
      override val wrap: String => String,
      override val editRange: Option[l.Range]
  ) extends WorkspaceMember(sym)

  class NamedArgMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

  class DependecyMember(val dependency: String, val edit: l.TextEdit)
      extends ScopeMember(
        completionsSymbol(dependency),
        NoType,
        true,
        EmptyTree
      ) {
    val label: String = dependency.stripPrefix(":")
    def isVersion: Boolean =
      label.takeWhile(_ != '-').forall(c => c.isDigit || c == '.')
  }

  class TextEditMember(
      val filterText: String,
      val edit: l.TextEdit,
      sym: Symbol,
      val label: Option[String] = None,
      val detail: Option[String] = None,
      val command: Option[String] = None,
      val additionalTextEdits: List[l.TextEdit] = Nil,
      val commitCharacter: Option[String] = None
  ) extends ScopeMember(sym, NoType, true, EmptyTree)

  class CasePatternMember(
      override val filterText: String,
      override val edit: l.TextEdit,
      sym: Symbol,
      override val label: Option[String] = None,
      override val detail: Option[String] = None,
      override val command: Option[String] = None,
      override val additionalTextEdits: List[l.TextEdit] = Nil
  ) extends TextEditMember(
        filterText,
        edit,
        sym,
        label,
        detail,
        command,
        additionalTextEdits,
        None
      )

  val packageSymbols: mutable.Map[String, Option[Symbol]] =
    mutable.Map.empty[String, Option[Symbol]]
  def packageSymbolFromString(symbol: String): Option[Symbol] =
    if (symbol == "_empty_/") Some(rootMirror.EmptyPackage)
    else {
      packageSymbols.getOrElseUpdate(
        symbol, {
          val fqn = symbol.stripSuffix("/").replace('/', '.')
          try {
            Some(rootMirror.staticPackage(fqn))
          } catch {
            case NonFatal(_) =>
              None
          }
        }
      )
    }

  /**
   * Returns a high number for less relevant symbols and low number for relevant numbers.
   *
   * Relevance is computed based on several factors such as
   * - local vs global
   * - public vs private
   * - synthetic vs non-synthetic
   */
  def relevancePenalty(m: Member): Int =
    m match {
      case tm: TypeMember if tm.accessible =>
        computeRelevancePenalty(
          tm.sym,
          m.implicitlyAdded,
          tm.inherited
        )
      case w: WorkspaceMember =>
        MemberOrdering.IsWorkspaceSymbol + w.sym.name.length()
      case w: OverrideDefMember =>
        var penalty = computeRelevancePenalty(
          w.sym,
          m.implicitlyAdded,
          isInherited = false
        ) >>> 15
        if (!w.sym.isAbstract) penalty |= MemberOrdering.IsNotAbstract
        penalty
      case sm: ScopeMember if sm.accessible =>
        computeRelevancePenalty(
          sm.sym,
          m.implicitlyAdded,
          isInherited = false
        )
      case _ =>
        Int.MaxValue
    }

  /**
   * Computes the relative relevance of a symbol in the completion list
   * This is an adaptation of
   * https://github.com/scala-ide/scala-ide/blob/a17ace0ee1be1875b8992664069d8ad26162eeee/org.scala-ide.sdt.core/src/org/scalaide/core/completion/ProposalRelevanceCalculator.scala
   */
  private def computeRelevancePenalty(
      sym: Symbol,
      viaImplicitConversion: Boolean,
      isInherited: Boolean
  ): Int = {
    import MemberOrdering._
    var relevance = 0
    // local symbols are more relevant
    if (!sym.isLocalToBlock) relevance |= IsNotLocalByBlock
    // symbols defined in this file are more relevant
    if (!sym.pos.isDefined || sym.hasPackageFlag)
      relevance |= IsNotDefinedInFile
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
    if (isEvilMethod(sym.name)) relevance |= IsEvilMethod
    relevance
  }

  lazy val isEvilMethod: Set[Name] = Set[Name](
    termNames.notifyAll_,
    termNames.notify_,
    termNames.wait_,
    termNames.clone_,
    termNames.finalize_
  )

  def memberOrdering(
      query: String,
      history: ShortenedNames,
      completion: CompletionPosition
  )(implicit queryInfo: PcQueryContext): Ordering[Member] =
    new Ordering[Member] {
      val queryLower = query.toLowerCase()
      val fuzzyCache = mutable.Map.empty[Symbol, Int]

      def compareLocalSymbols(o1: Member, o2: Member): Int = {
        if (
          o1.sym.isLocallyDefinedSymbol &&
          o2.sym.isLocallyDefinedSymbol &&
          !o1.isInstanceOf[NamedArgMember] &&
          !o2.isInstanceOf[NamedArgMember]
        ) {
          if (o1.sym.pos.isAfter(o2.sym.pos)) -1
          else 1
        } else {
          0
        }
      }
      def fuzzyScore(o: Member): Int = {
        fuzzyCache.getOrElseUpdate(
          o.sym, {
            val name = o.sym.name.toString().toLowerCase()
            if (name.startsWith(queryLower)) 0
            else if (name.toLowerCase().contains(queryLower)) 1
            else 2
          }
        )
      }

      private def workspaceMemberPriority(symbol: Symbol): Int =
        completionItemPriority
          .workspaceMemberPriority(
            semanticdbSymbol(symbol)
          )

      def compareFrequency(o1: Member, o2: Member): Int = {
        (o1, o2) match {
          case (w1: WorkspaceMember, w2: WorkspaceMember) =>
            workspaceMemberPriority(w1.sym)
              .compareTo(workspaceMemberPriority(w2.sym))
          case _ => 0
        }
      }

      override def compare(o1: Member, o2: Member): Int = {
        val byCompletion = completion.compare(o1, o2)
        if (byCompletion != 0) byCompletion
        else {
          val byLocalSymbol = compareLocalSymbols(o1, o2)
          if (byLocalSymbol != 0) byLocalSymbol
          else {
            val byRelevance = Integer.compare(
              relevancePenalty(o1),
              relevancePenalty(o2)
            )
            if (byRelevance != 0) byRelevance
            else {
              val byFuzzy =
                java.lang.Integer.compare(fuzzyScore(o1), fuzzyScore(o2))
              if (byFuzzy != 0) byFuzzy
              else {
                val byIdentifier =
                  IdentifierComparator.compare(
                    o1.sym.name.decode,
                    o2.sym.name.decode
                  )
                if (byIdentifier != 0) byIdentifier
                else {
                  val byFrequency = compareFrequency(o1, o2)
                  if (byFrequency != 0) byFrequency
                  else {
                    val byOwner =
                      o1.sym.owner.fullName.compareTo(o2.sym.owner.fullName)
                    if (byOwner != 0) byOwner
                    else {
                      val byParamCount = Integer.compare(
                        o1.sym.paramss.iterator.flatten.size,
                        o2.sym.paramss.iterator.flatten.size
                      )
                      if (byParamCount != 0) byParamCount
                      else {
                        detailString(o1, history)
                          .compareTo(detailString(o2, history))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

  def infoString(sym: Symbol, info: Type, history: ShortenedNames)(implicit
      queryInfo: PcQueryContext
  ): String =
    sym match {
      case m: MethodSymbol =>
        new SignaturePrinter(m, history, info, includeDocs = false)
          .defaultMethodSignature()
      case _ =>
        def fullName(s: Symbol): String =
          " " + s.owner.fullNameSyntax
        dealiasedValForwarder(sym) match {
          case dealiased :: _ =>
            fullName(dealiased)
          case _ =>
            if (
              sym.isModuleOrModuleClass || sym.hasPackageFlag || sym.isClass
            ) {
              fullName(sym)
            } else {
              val short = shortType(info, history)
              if (short == NoType) ""
              else sym.infoString(short).trim.replace(" <: <?>", "")
            }
        }
    }

  def completionsSymbol(name: String): Symbol = {
    definitions.ScalaPackage
      .newErrorSymbol(TermName(name))
      .setInfo(NoType)
  }

  def detailString(r: Member, history: ShortenedNames)(implicit
      queryInfo: PcQueryContext
  ): String = {
    if (!r.sym.hasPackageFlag) {
      // Compute type parameters based on the qualifier.
      // Example: Map[Int, String].applyOrE@@
      // Before: getOrElse[V1 >: V]     (key: K,   default: => V1): V1
      // After:  getOrElse[V1 >: String](key: Int, default: => V1): V1
      infoString(r.sym, r.prefix.memberType(r.sym), history)
    } else if (r.sym.hasRawInfo) {
      infoString(r.sym, r.sym.rawInfo, history)
    } else {
      "<_>"
    }
  }

  def dealiasedType(sym: Symbol): List[Symbol] = {
    if (sym.isAliasType) sym.dealiased :: Nil
    else Nil
  }
  def dealiasedValForwarder(sym: Symbol): List[Symbol] = {
    if (sym.isValue && sym.hasRawInfo && !sym.isLocallyDefined) {
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

  /**
   * Detects type member select from qualifiers that extend `scala.Dynamic`.
   *
   * By default, type member completions on classes that extend `scala.Dynamic`
   * return no results due to how `Dynamic` desugars trees. This traverser
   * detects such cases and run a custom type member completion.
   *
   * @param pos the position of the completion.
   */
  class DynamicFallbackCompletions(pos: Position) extends Traverser {
    var result: CompletionResult = CompletionResult.NoResults
    def print(): CompletionResult = {
      traverse(typedTreeAt(pos))
      result
    }
    override def traverse(tree: Tree): Unit = {
      tree match {
        case tree @ Apply(
              Select(qual, TermName("selectDynamic")),
              List(lit @ Literal(Constant(name: String)))
            ) if lit.pos.isTransparent && lit.pos.end >= tree.pos.end =>
          val typeMembers = metalsTypeMembers(tree.fun.pos).collect {
            case t: TypeMember => t
          }
          if (typeMembers.nonEmpty) {
            val termName = name.stripSuffix(CURSOR)
            result = CompletionResult.TypeMembers(
              pos.point - termName.length,
              qual,
              tree,
              typeMembers,
              TermName(termName)
            )
          }
          tree.setPos(tree.pos.withEnd(lit.pos.end))
        case _ =>
          if (tree.pos.includes(pos)) {
            super.traverse(tree)
          }
      }
    }
  }

  // NOTE(olafur): This variable keeps the list of all parent tree nodes
  // from the last visited tree node called from `typedTreeAt(pos)`
  // or `locateTree(pos)`. It's dirty to store this as a global mutable
  // variable but it avoids repeating traversals from the compiler
  // implementation of `completionsAt(pos)`.
  var lastVisitedParentTrees: List[Tree] = Nil

  def findLastVisitedParentTree(pos: Position): Option[Tree] = {
    if (lastVisitedParentTrees.isEmpty) {
      locateTree(pos)
    }
    lastVisitedParentTrees.headOption
  }

  abstract class CompletionPosition {
    def isType: Boolean = false
    def isNew: Boolean = false

    /**
     * Returns false if this member should be excluded from completion items.
     */
    def isCandidate(member: Member): Boolean = true

    /**
     * Returns ordering between two candidate completions.
     *
     * - negative number if o1 member should be sorted before o2
     * - positive number if o2 member should be sorted before o1
     * - zero 0 if o1 and o2 are equal
     */
    def compare(o1: Member, o2: Member): Int = {
      -java.lang.Boolean.compare(
        isPrioritizedCached(o1),
        isPrioritizedCached(o2)
      )
    }

    // Default implementation for `compare` delegates to the `isPrioritized` method
    // which groups prioritized members with first and non-prioritized second.
    final lazy val cache: mutable.Map[Symbol, Boolean] =
      mutable.Map.empty[Symbol, Boolean]
    final def isPrioritizedCached(m: Member): Boolean =
      cache.getOrElseUpdate(m.sym, isPrioritized(m))

    /**
     * Returns true if this member should be sorted at the top of completion items.
     */
    def isPrioritized(m: Member): Boolean = true

    /**
     * Returns candidate completion items.
     */
    def contribute: List[Member] = Nil

  }

  def completionPosition(
      pos: Position,
      source: URI,
      text: String,
      editRange: l.Range,
      completions: CompletionResult,
      latestEnclosing: List[Tree]
  )(implicit queryInfo: PcQueryContext): CompletionPosition = {
    // the implementation of completionPositionUnsafe does a lot of `typedTreeAt(pos).tpe`
    // which often causes null pointer exceptions, it's easier to catch the error here than
    // enforce discipline in the code.
    try
      completionPositionUnsafe(
        pos,
        source,
        text,
        editRange,
        completions,
        latestEnclosing
      )
    catch {
      case NonFatal(e) =>
        logger.log(Level.SEVERE, e.getMessage(), e)
        NoneCompletion
    }
  }
  def completionPositionUnsafe(
      pos: Position,
      source: URI,
      text: String,
      editRange: l.Range,
      completions: CompletionResult,
      latestEnclosingArg: List[Tree]
  )(implicit queryInfo: PcQueryContext): CompletionPosition = {
    val ScalaCliExtractor = new ScalaCliExtractor(pos)
    def fromIdentApply(
        ident: Ident,
        apply: Apply
    ): CompletionPosition = {
      if (hasLeadingBrace(ident, text)) {
        if (isCasePrefix(ident.name)) {
          val moveToNewLine = ident.pos.line == apply.pos.line
          val addNewLineAfter = apply.pos.focusEnd.line == ident.pos.line
          CaseKeywordCompletion(
            EmptyTree,
            editRange,
            pos,
            text,
            source,
            apply,
            includeExhaustive =
              Some(NewLineOptions(moveToNewLine, addNewLineAfter))
          )
        } else {
          NoneCompletion
        }
      } else {
        if (!isInfix(apply.fun, text)) {
          ArgCompletion(ident, apply, pos, text, completions)
        } else NoneCompletion
      }
    }

    latestEnclosingArg match {
      case MillIvyExtractor(dep) =>
        MillIvyCompletion(coursierComplete, pos, text, dep)
      case SbtLibExtractor(pos, dep) if pos.source.path.isSbt =>
        SbtLibCompletion(coursierComplete, pos, dep)
      case ScalaCliExtractor(dep) =>
        ScalaCliCompletion(coursierComplete, pos, text, dep)
      case _ if isMultilineCommentStart(pos, text) =>
        MultilineCommentCompletion(editRange, pos, text)
      case _ if isScaladocCompletion(pos, text) =>
        val associatedDef = onUnitOf(pos.source) { unit =>
          new AssociatedMemberDefFinder(pos).findAssociatedDef(unit.body)
        }
        associatedDef
          .map(definition =>
            ScaladocCompletion(editRange, Some(definition), pos, text)
          )
          .getOrElse(ScaladocCompletion(editRange, None, pos, text))
      case (ident: Ident) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case (ident: Ident) :: (_: Select) :: (_: Assign) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case (lit @ Literal(Constant(_: String))) :: head :: _ =>
        InterpolationSplice(pos.point, pos.source.content, text) match {
          case Some(i) =>
            InterpolatorScopeCompletion(lit, pos, i, text)
          case _ =>
            isPossibleInterpolatorMember(lit, head, text, pos)
              .getOrElse(NoneCompletion)
        }
      case CaseExtractors.CaseExtractor(selector, parent) =>
        CaseKeywordCompletion(
          selector,
          editRange,
          pos,
          text,
          source,
          parent
        )
      case CaseExtractors.CasePatternExtractor(selector, parent, name) =>
        CaseKeywordCompletion(
          selector,
          editRange,
          pos,
          text,
          source,
          parent,
          patternOnly = Some(name.stripSuffix("_CURSOR_"))
        )
      case CaseExtractors.TypedCasePatternExtractor(selector, parent, name) =>
        CaseKeywordCompletion(
          selector,
          editRange,
          pos,
          text,
          source,
          parent,
          patternOnly = Some(name.stripSuffix("_CURSOR_")),
          hasBind = true
        )
      case (c: DefTree) :: (p: PackageDef) :: _
          if c.namePosition.includes(pos) =>
        FilenameCompletion(c, p, pos, editRange)
      case OverrideExtractor(name, template, start, isCandidate) =>
        OverrideCompletion(
          name,
          template,
          pos,
          text,
          start,
          isCandidate
        )
      case (imp @ Import(select, selector)) :: _
          if isWorksheetIvyCompletionPosition(imp, pos) =>
        WorksheetIvyCompletion(
          coursierComplete,
          select,
          selector,
          pos,
          editRange,
          text
        )
      case _ =>
        inferCompletionPosition(
          pos,
          source,
          text,
          latestEnclosingArg,
          completions,
          editRange
        )
    }
  }

  def isWorksheetIvyCompletionPosition(tree: Tree, pos: Position): Boolean =
    tree match {
      case Import(select, _) =>
        pos.source.file.name.isWorksheet &&
        (select.toString().startsWith("<$ivy: error>") ||
          select.toString().startsWith("<$dep: error>"))
      case _ => false
    }

  private def inferCompletionPosition(
      pos: Position,
      source: URI,
      text: String,
      enclosing: List[Tree],
      completions: CompletionResult,
      editRange: l.Range
  )(implicit queryInfo: PcQueryContext): CompletionPosition = {
    object ExhaustiveMatch {
      def unapply(sel: Select): Option[CompletionPosition] = {
        if (!isMatchPrefix(sel.name)) None
        else if (
          sel.pos.point < 1 ||
          text.charAt(sel.pos.point - 1) != ' '
        ) None
        else {
          completions match {
            case t: CompletionResult.TypeMembers =>
              t.results.collectFirst {
                case result if result.prefix.isDefined =>
                  MatchKeywordCompletion(
                    result.prefix,
                    editRange,
                    pos,
                    source,
                    text
                  )
              }
            case _ =>
              None
          }
        }
      }
    }
    enclosing match {
      case ExhaustiveMatch(pos) :: _ =>
        pos
      case (_: Ident | _: Select) :: tail =>
        tail match {
          case (v: ValOrDefDef) :: _ =>
            if (v.tpt.pos.includes(pos)) {
              TypeCompletion
            } else {
              NoneCompletion
            }
          case _ =>
            inferCompletionPosition(
              pos,
              source,
              text,
              tail,
              completions,
              editRange
            )
        }
      case AppliedTypeTree(_, args) :: _ =>
        if (args.exists(_.pos.includes(pos))) {
          TypeCompletion
        } else {
          NoneCompletion
        }
      case New(_) :: _ =>
        NewCompletion
      case head :: tail if !head.pos.includes(pos) =>
        inferCompletionPosition(pos, source, text, tail, completions, editRange)
      case _ =>
        NoneCompletion
    }

  }

  class MetalsLocator(pos: Position, acceptTransparent: Boolean = false)
      extends Traverser {
    def locateIn(root: Tree): Tree = {
      lastVisitedParentTrees = Nil
      traverse(root)
      lastVisitedParentTrees match {
        case _ :: (sel @ Select(qual, name)) :: _
            if name == termNames.unapply && qual.pos.includes(pos) =>
          sel
        case head :: _ => head
        case _ => EmptyTree
      }
    }
    private def addToPath(path: List[Tree], t: Tree): List[Tree] = {
      def goesAfter(prev: Tree): Boolean =
        // in most cases prev tree should include next one
        // however it doesn't work for some synthetic trees
        // in this case check if both tree have different position ranges
        prev.pos.includes(t.pos) || !t.pos.includes(prev.pos)

      path match {
        case Nil => List(t)
        case head :: tl if goesAfter(head) => t :: head :: tl
        case head :: tl => head :: addToPath(tl, t)
      }
    }

    protected def isEligible(t: Tree): Boolean = {
      !t.pos.isTransparent || acceptTransparent || {
        t match {
          // new User(age = 42, name = "") becomes transparent, which doesn't happen with normal methods
          case Apply(Select(_: New, _), _) => true
          // for named args apply becomes transparent but fun doesn't
          case Apply(fun, args) =>
            !fun.pos.isTransparent && args.forall(_.pos.isOffset)
          // for synthetic val definition select on it becomes transparent
          case sel @ Select(qual: Ident, _) =>
            val qualifierIsSyntheticVal =
              Option(qual.symbol).exists(sym => sym.isValue && sym.isSynthetic)
            qualifierIsSyntheticVal &&
            !sel.namePosition.isTransparent && sel.namePosition.encloses(pos)
          // val (foo, bar) = (???, ???)
          // gets desugared case match
          case b @ Bind(_, Ident(_)) =>
            !b.namePosition.isTransparent && b.namePosition.encloses(pos)
          case _ => false
        }
      }
    }
    override def traverse(t: Tree): Unit = {
      t match {
        case tt: TypeTree
            if tt.original != null && (tt.pos includes tt.original.pos) =>
          traverse(tt.original)
        case _ =>
          if (t.pos.includes(pos)) {
            if (isEligible(t)) {
              lastVisitedParentTrees = addToPath(lastVisitedParentTrees, t)
            }
            super.traverse(t)
          } else {
            t match {
              // workaround for Scala 2.13,
              // where position is not set properly for synthetic val definition
              // we are interested only in its children, which have correct positions set
              case v: ValDef if v.pos.isOffset && v.symbol.isSynthetic =>
                super.traverse(v)
              case mdef: MemberDef =>
                val annTrees = mdef.mods.annotations match {
                  case Nil if mdef.symbol != null =>
                    // After typechecking, annotations are moved from the modifiers
                    // to the annotation on the symbol of the annotatee.
                    mdef.symbol.annotations.map(_.original)
                  case anns => anns
                }
                traverseTrees(annTrees)
              case _ =>
            }
          }
      }
    }
  }

  lazy val isNotOverridableName: Set[Name] =
    Iterator(
      definitions.syntheticCoreMethods.iterator.map(_.name),
      Iterator(
        termNames.notify_,
        termNames.notifyAll_,
        termNames.wait_,
        termNames.MIXIN_CONSTRUCTOR,
        termNames.CONSTRUCTOR
      )
    ).flatten.toSet -- Set[Name](
      termNames.hashCode_,
      termNames.toString_,
      termNames.equals_
    )

  lazy val isUninterestingSymbolOwner: Set[Symbol] = Set[Symbol](
    // The extension methods in these classes are noisy because they appear on every completion.
    definitions.getMemberClass(
      definitions.PredefModule,
      TypeName("ArrowAssoc")
    ),
    definitions.getMemberClass(
      definitions.PredefModule,
      TypeName("Ensuring")
    )
  )
  lazy val isUninterestingSymbol: Set[Symbol] = Set[Symbol](
    definitions.Any_==,
    definitions.Any_!=,
    definitions.Any_##,
    definitions.Object_==,
    definitions.Object_!=,
    definitions.Object_##,
    definitions.Object_eq,
    definitions.Object_ne,
    definitions.RepeatedParamClass,
    definitions.ByNameParamClass,
    definitions.JavaRepeatedParamClass,
    definitions.Object_notify,
    definitions.Object_notifyAll,
    definitions.Object_notify,
    definitions.getMemberMethod(definitions.ObjectClass, termNames.wait_),
    // NOTE(olafur) IntelliJ does not complete the root package and without this filter
    // then `_root_` would appear as a completion result in the code `foobar(_<COMPLETE>)`
    rootMirror.RootPackage,
    // NOTE(gabro) valueOf was added as a Predef member in 2.13. We filter it out since is a niche
    // use case and it would appear upon typing 'val'
    definitions.getMemberIfDefined(
      definitions.PredefModule,
      TermName("valueOf")
    )
  ).flatMap(_.safeAlternatives)

  lazy val renameConfig: collection.Map[Symbol, Name] =
    metalsConfig
      .symbolPrefixes()
      .asScala
      .map { case (sym, name) =>
        val nme =
          if (name.endsWith("#")) TypeName(name.stripSuffix("#"))
          else if (name.endsWith(".")) TermName(name.stripSuffix("."))
          else TermName(name)
        inverseSemanticdbSymbol(sym) -> nme
      }
      .filterKeys(_ != NoSymbol)
      .toMap

  // Infers the indentation at the completion position by counting the number of leading
  // spaces in the line.
  // For example:
  // class Main {
  //   def foo<COMPLETE> // inferred indent is 2 spaces.
  // }
  def inferIndent(lineStart: Int, text: String): (Int, Boolean) = {
    var i = 0
    var tabIndented = false
    while (
      lineStart + i < text.length && {
        val char = text.charAt(lineStart + i)
        if (char == '\t') {
          tabIndented = true
          true
        } else
          char == ' '
      }
    ) {
      i += 1
    }
    (i, tabIndented)
  }

  // Returns the symbols that have been renamed in this scope.
  // For example:
  // import java.lang.{Boolean => JBoolean}
  // class Main {
  //   // renamedSymbols: Map(j.l.Boolean => JBoolean)
  // }
  def renamedSymbols(context: Context): collection.Map[Symbol, Name] = {
    val result = mutable.Map.empty[Symbol, Name]
    context.imports.foreach { imp =>
      lazy val pre = imp.qual.tpe
      imp.tree.selectors.foreach { sel =>
        if (sel.rename != null) {
          val member = pre.member(sel.name)
          if (member != NoSymbol)
            result(member) = sel.rename
          member.companion match {
            case NoSymbol =>
            case companion =>
              if (companion != NoSymbol)
                result(companion) = sel.rename
          }
        }
      }
    }
    result
  }

  case class InferredIdentOffsets(
      start: Int,
      end: Int,
      strippedLeadingBacktick: Boolean,
      strippedTrailingBacktick: Boolean
  )

  def inferIdentOffsets(
      pos: Position,
      text: String
  ): InferredIdentOffsets = {

    // If we fail to find a tree, approximate with a heurstic about ident characters
    def fallbackStart: Int = {
      var i = pos.point - 1
      while (i >= 0 && Chars.isIdentifierPart(text.charAt(i))) {
        i -= 1
      }
      i + 1
    }
    def fallbackEnd: Int = {
      findEnd(false)
    }

    def findEnd(hasBacktick: Boolean): Int = {
      val predicate: Char => Boolean = if (hasBacktick) { (ch: Char) =>
        !Chars.isLineBreakChar(ch) && ch != '`'
      } else {
        Chars.isIdentifierPart(_)
      }

      var i = pos.point
      while (i < text.length && predicate(text.charAt(i))) {
        i += 1
      }
      i
    }
    def fallback =
      InferredIdentOffsets(fallbackStart, fallbackEnd, false, false)

    def refTreePos(refTree: RefTree): InferredIdentOffsets = {
      val refTreePos = treePos(refTree)
      var startPos = refTreePos.point
      var strippedLeadingBacktick = false
      if (text.charAt(startPos) == '`') {
        startPos += 1
        strippedLeadingBacktick = true
      }

      val endPos = findEnd(strippedLeadingBacktick)
      var strippedTrailingBacktick = false
      if (endPos < text.length) {
        if (text.charAt(endPos) == '`') {
          strippedTrailingBacktick = true
        }
      }
      InferredIdentOffsets(
        Math.min(startPos, pos.point),
        endPos,
        strippedLeadingBacktick,
        strippedTrailingBacktick
      )
    }

    def loop(enclosing: List[Tree]): InferredIdentOffsets =
      enclosing match {
        case Nil => fallback
        case head :: tl =>
          if (!treePos(head).includes(pos)) loop(tl)
          else {
            head match {
              case i: Ident =>
                refTreePos(i)
              case sel @ Select(qual, _) if !treePos(qual).includes(pos) =>
                refTreePos(sel)
              case _ => fallback
            }
          }
      }
    loop(lastVisitedParentTrees)
  }

  /**
   * Returns the start offset of the identifier starting as the given offset position.
   */
  def inferIdentStart(pos: Position, text: String): Int =
    inferIdentOffsets(pos, text).start

  def isSnippetEnabled(pos: Position, text: String): Boolean = {
    pos.point < text.length() && {
      text.charAt(pos.point) match {
        case ')' | ']' | '}' | ',' | '\n' => true
        case _ =>
          !text.startsWith(" _", pos.point) && {
            text.startsWith("\r\n", pos.point)
          }
      }
    }
  }
}
