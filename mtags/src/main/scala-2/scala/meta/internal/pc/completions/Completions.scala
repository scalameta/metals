package scala.meta.internal.pc.completions

import java.util.logging.Level

import scala.collection.immutable.Nil
import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.pc.IdentifierComparator
import scala.meta.internal.pc.MemberOrdering
import scala.meta.internal.pc.MetalsGlobal
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.tokenizers.Chars

import org.eclipse.{lsp4j => l}

/**
 * Utility methods for completions.
 */
trait Completions { this: MetalsGlobal =>

  val clientSupportsSnippets: Boolean =
    metalsConfig.isCompletionSnippetsEnabled()

  /**
   * A member for symbols on the classpath that are not in scope, produced via workspace/symbol.
   */
  class WorkspaceMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

  class NamedArgMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

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

  val packageSymbols: mutable.Map[String, Option[Symbol]] =
    mutable.Map.empty[String, Option[Symbol]]
  def packageSymbolFromString(symbol: String): Option[Symbol] = {
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
  def relevancePenalty(m: Member, history: ShortenedNames): Int =
    m match {
      case TypeMember(sym, _, true, isInherited, _) =>
        computeRelevancePenalty(
          sym,
          m.implicitlyAdded,
          isInherited,
          history
        )
      case w: WorkspaceMember =>
        MemberOrdering.IsWorkspaceSymbol + w.sym.name.length()
      case w: OverrideDefMember =>
        var penalty = computeRelevancePenalty(
          w.sym,
          m.implicitlyAdded,
          isInherited = false,
          history
        ) >>> 15
        if (!w.sym.isAbstract) penalty |= MemberOrdering.IsNotAbstract
        penalty
      case ScopeMember(sym, _, true, _) =>
        computeRelevancePenalty(
          sym,
          m.implicitlyAdded,
          isInherited = false,
          history
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
      isInherited: Boolean,
      history: ShortenedNames
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
  ): Ordering[Member] =
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
      override def compare(o1: Member, o2: Member): Int = {
        val byCompletion = completion.compare(o1, o2)
        if (byCompletion != 0) byCompletion
        else {
          val byLocalSymbol = compareLocalSymbols(o1, o2)
          if (byLocalSymbol != 0) byLocalSymbol
          else {
            val byRelevance = Integer.compare(
              relevancePenalty(o1, history),
              relevancePenalty(o2, history)
            )
            if (byRelevance != 0) byRelevance
            else {
              val byFuzzy =
                java.lang.Integer.compare(fuzzyScore(o1), fuzzyScore(o2))
              if (byFuzzy != 0) byFuzzy
              else {
                val byIdentifier =
                  IdentifierComparator.compare(o1.sym.name, o2.sym.name)
                if (byIdentifier != 0) byIdentifier
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

  def infoString(sym: Symbol, info: Type, history: ShortenedNames): String =
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

  def detailString(r: Member, history: ShortenedNames): String = {
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
      text: String,
      editRange: l.Range,
      completions: CompletionResult,
      latestEnclosing: List[Tree]
  ): CompletionPosition = {
    // the implementation of completionPositionUnsafe does a lot of `typedTreeAt(pos).tpe`
    // which often causes null pointer exceptions, it's easier to catch the error here than
    // enforce discipline in the code.
    try completionPositionUnsafe(
      pos,
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
      text: String,
      editRange: l.Range,
      completions: CompletionResult,
      latestEnclosingArg: List[Tree]
  ): CompletionPosition = {
    val PatternMatch = new PatternMatch(pos)
    def fromIdentApply(
        ident: Ident,
        apply: Apply
    ): CompletionPosition = {
      if (hasLeadingBrace(ident, text)) {
        if (isCasePrefix(ident.name)) {
          CaseKeywordCompletion(EmptyTree, editRange, pos, text, apply)
        } else {
          NoneCompletion
        }
      } else {
        ArgCompletion(ident, apply, pos, text, completions)
      }
    }

    latestEnclosingArg match {
      case _ if isScaladocCompletion(pos, text) =>
        val associatedDef = onUnitOf(pos.source) { unit =>
          new AssociatedMemberDefFinder(pos).findAssociatedDef(unit.body)
        }
        associatedDef
          .map(definition =>
            ScaladocCompletion(editRange, definition, pos, text)
          )
          .getOrElse(NoneCompletion)
      case (ident: Ident) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case (ident: Ident) :: (_: Select) :: (_: Assign) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case Ident(_) :: PatternMatch(c, m) =>
        CasePatternCompletion(isTyped = false, c, m)
      case Ident(_) :: Typed(_, _) :: PatternMatch(c, m) =>
        CasePatternCompletion(isTyped = true, c, m)
      case (lit @ Literal(Constant(_: String))) :: head :: _ =>
        isPossibleInterpolatorSplice(pos, text) match {
          case Some(i) =>
            InterpolatorScopeCompletion(lit, pos, i, text)
          case _ =>
            isPossibleInterpolatorMember(lit, head, text, pos)
              .getOrElse(NoneCompletion)
        }
      case (_: Ident) ::
          Select(Ident(TermName("scala")), TypeName("Unit")) ::
          (defdef: DefDef) ::
          (t: Template) :: _ if defdef.name.endsWith(CURSOR) =>
        OverrideCompletion(
          defdef.name,
          t,
          pos,
          text,
          defdef.pos.start,
          !_.isGetter
        )
      case (valdef @ ValDef(_, name, _, Literal(Constant(null)))) ::
          (t: Template) :: _ if name.endsWith(CURSOR) =>
        OverrideCompletion(
          name,
          t,
          pos,
          text,
          valdef.pos.start,
          _ => true
        )
      case (m @ Match(_, Nil)) :: parent :: _ =>
        CaseKeywordCompletion(m.selector, editRange, pos, text, parent)
      case Ident(name) :: (_: CaseDef) :: (m: Match) :: parent :: _
          if isCasePrefix(name) =>
        CaseKeywordCompletion(m.selector, editRange, pos, text, parent)
      case (ident @ Ident(name)) :: Block(
            _,
            expr
          ) :: (_: CaseDef) :: (m: Match) :: parent :: _
          if ident == expr && isCasePrefix(name) =>
        CaseKeywordCompletion(m.selector, editRange, pos, text, parent)
      case (c: DefTree) :: (p: PackageDef) :: _ if c.namePos.includes(pos) =>
        FilenameCompletion(c, p, pos, editRange)
      case (ident: Ident) :: (t: Template) :: _ =>
        OverrideCompletion(
          ident.name,
          t,
          pos,
          text,
          ident.pos.start,
          _ => true
        )
      case _ =>
        inferCompletionPosition(
          pos,
          text,
          latestEnclosingArg,
          completions,
          editRange
        )
    }
  }

  private def inferCompletionPosition(
      pos: Position,
      text: String,
      enclosing: List[Tree],
      completions: CompletionResult,
      editRange: l.Range
  ): CompletionPosition = {
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
      case ExhaustiveMatch(pos) :: tail =>
        pos
      case (head @ (_: Ident | _: Select)) :: tail =>
        tail match {
          case (v: ValOrDefDef) :: _ =>
            if (v.tpt.pos.includes(pos)) {
              TypeCompletion
            } else {
              NoneCompletion
            }
          case _ =>
            inferCompletionPosition(pos, text, tail, completions, editRange)
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
        inferCompletionPosition(pos, text, tail, completions, editRange)
      case _ =>
        NoneCompletion
    }

  }

  class PatternMatch(pos: Position) {
    def unapply(enclosing: List[Tree]): Option[(CaseDef, Match)] =
      enclosing match {
        case (c: CaseDef) :: (m: Match) :: _ if c.pat.pos.includes(pos) =>
          Some((c, m))
        case _ =>
          None
      }
  }
  class MetalsLocator(pos: Position) extends Traverser {
    def locateIn(root: Tree): Tree = {
      lastVisitedParentTrees = Nil
      traverse(root)
      lastVisitedParentTrees match {
        case head :: _ => head
        case _ => EmptyTree
      }
    }
    protected def isEligible(t: Tree): Boolean = !t.pos.isTransparent
    override def traverse(t: Tree): Unit = {
      t match {
        case tt: TypeTree
            if tt.original != null && (tt.pos includes tt.original.pos) =>
          traverse(tt.original)
        case _ =>
          if (t.pos includes pos) {
            if (isEligible(t)) {
              lastVisitedParentTrees ::= t
            }
            super.traverse(t)
          } else {
            t match {
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
  ).flatMap(_.alternatives)

  lazy val renameConfig: collection.Map[Symbol, Name] =
    metalsConfig
      .symbolPrefixes()
      .asScala
      .map {
        case (sym, name) =>
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
  def inferIndent(lineStart: Int, text: String): Int = {
    var i = 0
    while (lineStart + i < text.length && text.charAt(lineStart + i) == ' ') {
      i += 1
    }
    i
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
          result(member) = sel.rename
          member.companion match {
            case NoSymbol =>
            case companion =>
              result(companion) = sel.rename
          }
        }
      }
    }
    result
  }

  /**
   * Returns the start offset of the identifier starting as the given offset position.
   */
  def inferIdentStart(pos: Position, text: String): Int = {
    def fallback: Int = {
      var i = pos.point - 1
      while (i > 0 && Chars.isIdentifierPart(text.charAt(i))) {
        i -= 1
      }
      i + 1
    }
    def loop(enclosing: List[Tree]): Int =
      enclosing match {
        case Nil => fallback
        case head :: tl =>
          if (!treePos(head).includes(pos)) loop(tl)
          else {
            head match {
              case i: Ident =>
                treePos(i).point
              case Select(qual, nme) if !treePos(qual).includes(pos) =>
                treePos(head).point
              case _ => fallback
            }
          }
      }
    loop(lastVisitedParentTrees)
  }

  /**
   * Returns the end offset of the identifier starting as the given offset position.
   */
  def inferIdentEnd(pos: Position, text: String): Int = {
    var i = pos.point
    while (i < text.length && Chars.isIdentifierPart(text.charAt(i))) {
      i += 1
    }
    i
  }

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
