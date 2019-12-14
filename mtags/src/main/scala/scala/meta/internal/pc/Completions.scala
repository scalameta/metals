package scala.meta.internal.pc

import java.lang.StringBuilder
import java.net.URI
import java.nio.file.Paths
import java.util.logging.Level

import org.eclipse.{lsp4j => l}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.tokenizers.Chars
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.util.control.NonFatal
import scala.collection.immutable.Nil

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

  class OverrideDefMember(
      val label: String,
      val edit: l.TextEdit,
      val filterText: String,
      sym: Symbol,
      val autoImports: List[l.TextEdit],
      val detail: String
  ) extends ScopeMember(sym, NoType, true, EmptyTree)

  val packageSymbols: mutable.Map[String, Option[Symbol]] =
    mutable.Map.empty[String, Option[Symbol]]
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
  def relevancePenalty(m: Member, history: ShortenedNames): Int = m match {
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

  /** Computes the relative relevance of a symbol in the completion list
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
  ): Ordering[Member] = new Ordering[Member] {
    val queryLower = query.toLowerCase()
    val fuzzyCache = mutable.Map.empty[Symbol, Int]
    def compareLocalSymbols(o1: Member, o2: Member): Int = {
      if (o1.sym.isLocallyDefinedSymbol &&
        o2.sym.isLocallyDefinedSymbol &&
        !o1.isInstanceOf[NamedArgMember] &&
        !o2.isInstanceOf[NamedArgMember]) {
        if (o1.sym.pos.isAfter(o2.sym.pos)) -1
        else 1
      } else {
        0
      }
    }
    def fuzzyScore(o: Member): Int = {
      fuzzyCache.getOrElseUpdate(o.sym, {
        val name = o.sym.name.toString().toLowerCase()
        if (name.startsWith(queryLower)) 0
        else if (name.toLowerCase().contains(queryLower)) 1
        else 2
      })
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
            if (sym.isModuleOrModuleClass || sym.hasPackageFlag || sym.isClass) {
              fullName(sym)
            } else {
              val short = shortType(info, history)
              if (short == NoType) ""
              else sym.infoString(short).replaceAllLiterally(" <: <?>", "")
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
  var lastVisistedParentTrees: List[Tree] = Nil
  sealed abstract class CompletionPosition {
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

    def isPrioritized(m: Member): Boolean = true

    /**
     * Returns true if this member should be sorted at the top of completion items.
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
        CompletionPosition.None
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
      if (CompletionPosition.hasLeadingBrace(ident, text)) {
        if (isCasePrefix(ident.name)) {
          CompletionPosition.CaseKeyword(EmptyTree, editRange, pos, text, apply)
        } else {
          CompletionPosition.None
        }
      } else {
        CompletionPosition.Arg(ident, apply, pos, text, completions)
      }
    }

    latestEnclosingArg match {
      case (ident: Ident) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case (ident: Ident) :: (_: Select) :: (_: Assign) :: (a: Apply) :: _ =>
        fromIdentApply(ident, a)
      case Ident(_) :: PatternMatch(c, m) =>
        CompletionPosition.CasePattern(isTyped = false, c, m)
      case Ident(_) :: Typed(_, _) :: PatternMatch(c, m) =>
        CompletionPosition.CasePattern(isTyped = true, c, m)
      case (lit @ Literal(Constant(_: String))) :: head :: _ =>
        isPossibleInterpolatorSplice(pos, text) match {
          case Some(i) =>
            CompletionPosition.InterpolatorScope(lit, pos, i, text)
          case _ =>
            isPossibleInterpolatorMember(lit, head, text, pos)
              .getOrElse(CompletionPosition.None)
        }
      case (_: Ident) ::
            Select(Ident(TermName("scala")), TypeName("Unit")) ::
            (defdef: DefDef) ::
            (t: Template) :: _ if defdef.name.endsWith(CURSOR) =>
        CompletionPosition.Override(
          defdef.name,
          t,
          pos,
          text,
          defdef.pos.start,
          !_.isGetter
        )
      case (valdef @ ValDef(_, name, _, Literal(Constant(null)))) ::
            (t: Template) :: _ if name.endsWith(CURSOR) =>
        CompletionPosition.Override(
          name,
          t,
          pos,
          text,
          valdef.pos.start,
          _ => true
        )
      case (m @ Match(_, Nil)) :: parent :: _ =>
        CompletionPosition.CaseKeyword(m.selector, editRange, pos, text, parent)
      case Ident(name) :: (_: CaseDef) :: (m: Match) :: parent :: _
          if isCasePrefix(name) =>
        CompletionPosition.CaseKeyword(m.selector, editRange, pos, text, parent)
      case (ident @ Ident(name)) :: Block(_, expr) :: (_: CaseDef) :: (m: Match) :: parent :: _
          if ident == expr && isCasePrefix(name) =>
        CompletionPosition.CaseKeyword(m.selector, editRange, pos, text, parent)
      case (c: DefTree) :: (p: PackageDef) :: _ if c.namePos.includes(pos) =>
        CompletionPosition.Filename(c, p, pos, editRange)
      case (ident: Ident) :: (t: Template) :: _ =>
        CompletionPosition.Override(
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

  def isCasePrefix(name: Name): Boolean = {
    val prefix = name.decoded.stripSuffix(CURSOR)
    Set("c", "ca", "cas", "case").contains(prefix)
  }

  def interpolatorMemberArg(parent: Tree, lit: Literal): Option[Ident] =
    parent match {
      case Apply(
          Select(
            Apply(Ident(TermName("StringContext")), _ :: parts),
            _
          ),
          args
          ) =>
        parts.zip(args).collectFirst {
          case (`lit`, i: Ident) => i
        }
      case _ =>
        None
    }

  def interpolatorMemberSelect(lit: Literal): Option[String] = lit match {
    case Literal(Constant(s: String)) =>
      if (s.startsWith(s".$CURSOR")) Some("")
      else if (s.startsWith(".") &&
        s.length > 2 &&
        s.charAt(1).isUnicodeIdentifierStart) {
        val cursor = s.indexOf(CURSOR)
        if (cursor < 0) None
        else {
          val isValidIdentifier =
            2.until(cursor).forall(i => s.charAt(i).isUnicodeIdentifierPart)
          if (isValidIdentifier) {
            Some(s.substring(1, cursor))
          } else {
            None
          }
        }
      } else {
        None
      }
    case _ =>
      None
  }

  def isPossibleInterpolatorMember(
      lit: Literal,
      parent: Tree,
      text: String,
      cursor: Position
  ): Option[CompletionPosition.InterpolatorType] = {
    for {
      query <- interpolatorMemberSelect(lit)
      if text.charAt(lit.pos.point - 1) != '}'
      arg <- interpolatorMemberArg(parent, lit)
    } yield {
      CompletionPosition.InterpolatorType(
        query,
        arg,
        lit,
        cursor,
        text
      )
    }
  }

  case class InterpolationSplice(
      dollar: Int,
      name: String,
      needsBraces: Boolean
  )
  def isPossibleInterpolatorSplice(
      pos: Position,
      text: String
  ): Option[InterpolationSplice] = {
    val offset = pos.point
    val chars = pos.source.content
    var i = offset
    while (i > 0 && (chars(i) match { case '$' | '\n' => false; case _ => true })) {
      i -= 1
    }
    val isCandidate = i > 0 &&
      chars(i) == '$' && {
      val start = chars(i + 1) match {
        case '{' => i + 2
        case _ => i + 1
      }
      start == offset || {
        chars(start).isUnicodeIdentifierStart &&
        (start + 1).until(offset).forall(j => chars(j).isUnicodeIdentifierPart)
      }
    }
    if (isCandidate) {
      val name = chars(i + 1) match {
        case '{' => text.substring(i + 2, offset)
        case _ => text.substring(i + 1, offset)
      }
      Some(
        InterpolationSplice(
          i,
          name,
          needsBraces = text.charAt(i + 1) == '{' ||
            (text.charAt(offset) match {
              case '"' => false // end of string literal
              case ch => ch.isUnicodeIdentifierPart
            })
        )
      )
    } else {
      None
    }
  }

  def isMatchPrefix(name: Name): Boolean =
    name.endsWith(CURSOR) &&
      "match".startsWith(name.toString().stripSuffix(CURSOR))

  def inferCompletionPosition(
      pos: Position,
      text: String,
      enclosing: List[Tree],
      completions: CompletionResult,
      editRange: l.Range
  ): CompletionPosition = {
    object ExhaustiveMatch {
      def unapply(sel: Select): Option[CompletionPosition] = {
        if (!isMatchPrefix(sel.name)) None
        else if (sel.pos.point < 1 ||
          text.charAt(sel.pos.point - 1) != ' ') None
        else {
          completions match {
            case t: CompletionResult.TypeMembers =>
              t.results.collectFirst {
                case result if result.prefix.isDefined =>
                  CompletionPosition.MatchKeyword(
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
              CompletionPosition.Type
            } else {
              CompletionPosition.None
            }
          case _ =>
            inferCompletionPosition(pos, text, tail, completions, editRange)
        }
      case AppliedTypeTree(_, args) :: _ =>
        if (args.exists(_.pos.includes(pos))) {
          CompletionPosition.Type
        } else {
          CompletionPosition.None
        }
      case New(_) :: _ =>
        CompletionPosition.New
      case head :: tail if !head.pos.includes(pos) =>
        inferCompletionPosition(pos, text, tail, completions, editRange)
      case _ =>
        CompletionPosition.None
    }
  }

  object CompletionPosition {

    /**
     * A completion inside a type position, example `val x: Map[Int, Strin@@]`
     */
    case object Type extends CompletionPosition {
      override def isType: Boolean = true
    }

    /**
     * A completion inside a new expression, example `new Array@@`
     */
    case object New extends CompletionPosition {
      override def isNew: Boolean = true
    }

    /**
     * A completion to select type members inside string interpolators.
     *
     * Example: {{{
     *   // before
     *   s"Hello $name.len@@!"
     *   // after
     *   s"Hello ${name.length()$0}"
     * }}}
     *
     * @param query the member query, "len" in the  example above.
     * @param ident the identifier from where we select a member from, "name" above.
     * @param literalPart the string literal part of the interpolator trailing
     *                    the identifier including cursor instrumentation, "len_CURSOR_!"
     *                    in the example above.
     * @param cursor the cursor position where the completion is triggered, `@@` in the example above.
     * @param text the text of the original source file without `_CURSOR_` instrumentation.
     */
    case class InterpolatorType(
        query: String,
        ident: Ident,
        literalPart: Literal,
        cursor: Position,
        text: String
    ) extends CompletionPosition {
      val pos: l.Range = ident.pos.withEnd(cursor.point).toLSP
      def newText(sym: Symbol): String = {
        new StringBuilder()
          .append('{')
          .append(text, ident.pos.start, ident.pos.end)
          .append('.')
          .append(Identifier.backtickWrap(sym.getterName.decoded))
          .append(sym.snippetCursor)
          .append('}')
          .toString
      }
      val filter: String =
        text.substring(ident.pos.start - 1, cursor.point - query.length)
      override def contribute: List[Member] = {
        metalsTypeMembers(ident.pos).collect {
          case m if CompletionFuzzy.matches(query, m.sym.name) =>
            val edit = new l.TextEdit(pos, newText(m.sym))
            val filterText = filter + m.sym.name.decoded
            new TextEditMember(filterText, edit, m.sym)
        }
      }
    }

    /**
     * A completion to convert a string literal into a string literal, example `"Hello $na@@"`.
     *
     * When converting a string literal into an interpolator we need to ensure a few cases:
     *
     * - escape existing `$` characters into `$$`, which are printed as `\$\$` in order to
     *   escape the TextMate snippet syntax.
     * - wrap completed name in curly braces `s"Hello ${name}_` when the trailing character
     *   can be treated as an identifier part.
     * - insert the  leading `s` interpolator.
     * - place the cursor at the end of the completed name using TextMate `$0` snippet syntax.
     *
     * @param lit The string literal, includes an instrumented `_CURSOR_` that we need to handle.
     * @param pos The offset position of the cursor, right below `@@_CURSOR_`.
     * @param interpolator Metadata about this interpolation, the location of the leading dollar
     *                     character and whether the completed name needs to be wrapped in
     *                     curly braces.
     * @param text The text of the original source code without the instrumented `_CURSOR_`.
     */
    case class InterpolatorScope(
        lit: Literal,
        pos: Position,
        interpolator: InterpolationSplice,
        text: String
    ) extends CompletionPosition {

      val offset: Int =
        if (lit.pos.focusEnd.line == pos.line) CURSOR.length else 0
      val nameStart: Position =
        pos.withStart(pos.start - interpolator.name.size)
      val nameRange = nameStart.toLSP
      val hasClosingBrace: Boolean = text.charAt(pos.point) == '}'
      val hasOpeningBrace: Boolean = text.charAt(
        pos.start - interpolator.name.size - 1
      ) == '{'

      def additionalEdits(): List[l.TextEdit] = {
        val interpolatorEdit =
          if (text.charAt(lit.pos.start - 1) != 's')
            List(new l.TextEdit(lit.pos.withEnd(lit.pos.start).toLSP, "s"))
          else Nil
        val dollarEdits = for {
          i <- lit.pos.start to (lit.pos.end - CURSOR.length())
          if text.charAt(i) == '$' && i != interpolator.dollar
        } yield new l.TextEdit(pos.source.position(i).withEnd(i).toLSP, "$")
        interpolatorEdit ++ dollarEdits
      }

      def newText(sym: Symbol): String = {
        val out = new StringBuilder()
        val symbolName = sym.getterName.decoded
        val identifier = Identifier.backtickWrap(symbolName)
        val symbolNeedsBraces =
          interpolator.needsBraces ||
            identifier.startsWith("`") ||
            sym.isNonNullaryMethod
        if (symbolNeedsBraces && !hasOpeningBrace) {
          out.append('{')
        }
        out.append(identifier)
        out.append(sym.snippetCursor)
        if (symbolNeedsBraces && !hasClosingBrace) {
          out.append('}')
        }
        out.toString
      }

      val filter: String =
        text.substring(lit.pos.start, pos.point - interpolator.name.length)

      override def contribute: List[Member] = {
        metalsScopeMembers(pos).collect {
          case s: ScopeMember
              if CompletionFuzzy.matches(interpolator.name, s.sym.name) =>
            val edit = new l.TextEdit(nameRange, newText(s.sym))
            val filterText = filter + s.sym.name.decoded
            new TextEditMember(
              filterText,
              edit,
              s.sym,
              additionalTextEdits = additionalEdits()
            )
        }
      }
    }

    /**
     * Completion for the name of a toplevel class, trait or object matching the filename.
     *
     * Example: {{{
     *   // src/main/scala/app/UserDatabaseService.scala
     *   class User@@ // completes "UserDatabaseService"
     * }}}
     *
     * @param toplevel the toplevel class, trait or object definition.
     * @param pkg the enclosing package definition.
     * @param pos the completion position.
     * @param editRange the range to replace in the completion.
     */
    case class Filename(
        toplevel: DefTree,
        pkg: PackageDef,
        pos: Position,
        editRange: l.Range
    ) extends CompletionPosition {
      val query: String = toplevel.name.toString().stripSuffix(CURSOR)
      override def contribute: List[Member] = {
        try {
          val name = Paths
            .get(URI.create(pos.source.file.name))
            .getFileName()
            .toString()
            .stripSuffix(".scala")
          val isTermName = toplevel.name.isTermName
          val siblings = pkg.stats.count {
            case d: DefTree =>
              d.name.toString() == name &&
                d.name.isTermName == isTermName
            case _ => false
          }
          if (!name.isEmpty &&
            CompletionFuzzy.matches(query, name) &&
            siblings == 0) {
            List(
              new TextEditMember(
                name,
                new l.TextEdit(editRange, name),
                toplevel.symbol
                  .newErrorSymbol(TermName(name))
                  .setInfo(NoType),
                label = Some(s"${toplevel.symbol.keyString} ${name}")
              )
            )
          } else {
            Nil
          }
        } catch {
          case NonFatal(e) =>
            Nil
        }
      }
    }

    case object None extends CompletionPosition

    /** Returns true if the identifier comes after an opening brace character '{' */
    def hasLeadingBrace(ident: Ident, text: String): Boolean = {
      val openDelim: Int = {
        var start = ident.pos.start - 1
        while (start > 0 && text.charAt(start).isWhitespace) {
          start -= 1
        }
        start
      }
      text.length > openDelim &&
      openDelim >= 0 &&
      text.charAt(openDelim) == '{'
    }

    case class Arg(
        ident: Ident,
        apply: Apply,
        pos: Position,
        text: String,
        completions: CompletionResult
    ) extends CompletionPosition {
      val editRange: l.Range =
        pos.withStart(ident.pos.start).withEnd(pos.start).toLSP
      val method: Tree = typedTreeAt(apply.fun.pos)
      val methodSym = method.symbol
      lazy val baseParams: List[Symbol] =
        if (method.tpe == null) Nil
        else {
          method.tpe.paramss.headOption
            .getOrElse(methodSym.paramss.flatten)
        }
      lazy val isNamed: Set[Name] = apply.args.iterator
        .filterNot(_ == ident)
        .zip(baseParams.iterator)
        .map {
          case (AssignOrNamedArg(Ident(name), _), _) =>
            name
          case (_, param) =>
            param.name
        }
        .toSet
      val prefix: String = ident.name.toString.stripSuffix(CURSOR)
      lazy val allParams: List[Symbol] = {
        baseParams.iterator.filterNot { param =>
          isNamed(param.name) ||
          param.name.containsChar('$') // exclude synthetic parameters
        }.toList
      }
      lazy val params: List[Symbol] =
        allParams.filter(param => param.name.startsWith(prefix))
      lazy val isParamName: Set[String] = params.iterator
        .map(_.name)
        .filterNot(isNamed)
        .map(_.toString().trim())
        .toSet

      override def isCandidate(member: Member): Boolean = true

      def isName(m: Member): Boolean =
        isParamName(m.sym.nameString.trim())

      override def compare(o1: Member, o2: Member): Int = {
        val byName = -java.lang.Boolean.compare(isName(o1), isName(o2))
        if (byName != 0) byName
        else {
          java.lang.Boolean.compare(
            o1.isInstanceOf[NamedArgMember],
            o2.isInstanceOf[NamedArgMember]
          )
        }
      }

      override def isPrioritized(member: Member): Boolean = {
        member.isInstanceOf[NamedArgMember] ||
        isParamName(member.sym.name.toString().trim())
      }

      private def matchingTypesInScope(
          paramType: Type
      ): List[String] = {

        def notNothingOrNull(mem: ScopeMember): Boolean = {
          !(mem.sym.tpe =:= definitions.NothingTpe || mem.sym.tpe =:= definitions.NullTpe)
        }

        completions match {
          case CompletionResult.ScopeMembers(positionDelta, results, name) =>
            results
              .collect {
                case mem
                    if mem.sym.tpe <:< paramType && notNothingOrNull(mem) && mem.sym.isTerm =>
                  mem.sym.name.toString().trim()
              }
              // None and Nil are always in scope
              .filter(name => name != "Nil" && name != "None")
          case _ =>
            Nil
        }
      }

      private def findDefaultValue(param: Symbol): String = {
        val matchingType = matchingTypesInScope(param.tpe)
        if (matchingType.size == 1) {
          s":${matchingType.head}"
        } else if (matchingType.size > 1) {
          s"|???,${matchingType.mkString(",")}|"
        } else {
          ":???"
        }
      }

      private def fillAllFields(): List[TextEditMember] = {
        val suffix = "autofill"
        val shouldShow =
          allParams.exists(param => param.name.startsWith(prefix))
        val isExplicitlyCalled = suffix.startsWith(prefix)
        val hasParamsToFill = allParams.count(!_.hasDefault) > 1
        if ((shouldShow || isExplicitlyCalled) && hasParamsToFill && clientSupportsSnippets) {
          val editText = allParams.zipWithIndex
            .collect {
              case (param, index) if !param.hasDefault =>
                s"${param.name} = $${${index + 1}${findDefaultValue(param)}}"
            }
            .mkString(", ")
          val edit = new l.TextEdit(editRange, editText)
          List(
            new TextEditMember(
              filterText = s"$prefix-$suffix",
              edit = edit,
              methodSym,
              label = Some("Autofill with default values")
            )
          )
        } else {
          List.empty
        }
      }

      private def findPossibleDefaults(): List[TextEditMember] = {
        params.flatMap { param =>
          val allMemebers = matchingTypesInScope(param.tpe)
          allMemebers.map { memberName =>
            val editText = param.name + " = " + memberName
            val edit = new l.TextEdit(editRange, editText)
            new TextEditMember(
              filterText = param.name.toString(),
              edit = edit,
              completionsSymbol(s"$param=$memberName"),
              label = Some(editText),
              detail = Some(" : " + param.tpe)
            )
          }
        }
      }

      override def contribute: List[Member] = {
        params.map(param => new NamedArgMember(param)) ::: findPossibleDefaults() ::: fillAllFields()
      }
    }

    /**
     * An `override def` completion to implement methods from the supertype.
     *
     * @param name the name of the method being completed including the `_CURSOR_` suffix.
     * @param t the enclosing template for the class/object/trait we are implementing.
     * @param pos the position of the completion request, points to `_CURSOR_`.
     * @param text the text of the original source code without `_CURSOR_`.
     * @param start the position start of the completion.
     * @param isCandidate the determination of whether the symbol will be a possible completion item.
     */
    case class Override(
        name: Name,
        t: Template,
        pos: Position,
        text: String,
        start: Int,
        isCandidate: Symbol => Boolean
    ) extends CompletionPosition {
      val prefix: String = name.toString.stripSuffix(CURSOR)
      val typed: Tree = typedTreeAt(t.pos)
      val isDecl: Set[Symbol] = typed.tpe.decls.toSet
      val range: l.Range = pos.withStart(start).withEnd(pos.point).toLSP
      val lineStart: RunId = pos.source.lineToOffset(pos.line - 1)

      // Returns all the symbols of all transitive supertypes in the enclosing scope.
      // For example:
      // class Main extends Serializable {
      //   class Inner {
      //     // parentSymbols: List(Main, Serializable, Inner)
      //   }
      // }
      def parentSymbols(context: Context): collection.Set[Symbol] = {
        val isVisited = mutable.Set.empty[Symbol]
        var cx = context

        def expandParent(parent: Symbol): Unit = {
          if (!isVisited(parent)) {
            isVisited.add(parent)
            parent.parentSymbols.foreach { parent =>
              expandParent(parent)
            }
          }
        }

        while (cx != NoContext && !cx.owner.hasPackageFlag) {
          expandParent(cx.owner)
          cx = cx.outer
        }
        isVisited
      }

      // NOTE(gabro): sym.isVar does not work consistently across Scala versions
      // Specifically, it behaves differently between 2.11 and 2.12/2.13
      // This check is borrowed from
      // https://github.com/scala/scala/blob/f389823ef0416612a0058a80c1fe85948ff5fc0a/src/reflect/scala/reflect/internal/Symbols.scala#L2645
      def isVarSetter(sym: Symbol): Boolean =
        !sym.isStable && !sym.isLazy && sym.isAccessor

      // Returns true if this symbol is a method that we can override.
      def isOverridableMethod(sym: Symbol): Boolean = {
        sym.isMethod &&
        !isDecl(sym) &&
        !isNotOverridableName(sym.name) &&
        !sym.isPrivate &&
        !sym.isSynthetic &&
        !sym.isArtifact &&
        !sym.isEffectivelyFinal &&
        !sym.name.endsWith(CURSOR) &&
        !sym.isConstructor &&
        (!isVarSetter(sym) || (isVarSetter(sym) && sym.isAbstract)) &&
        !sym.isSetter &&
        isCandidate(sym)
      }

      val context: Context = doLocateContext(pos)
      val baseAutoImport: Option[AutoImportPosition] =
        autoImportPosition(pos, text)
      val autoImport: AutoImportPosition = baseAutoImport.getOrElse(
        AutoImportPosition(
          lineStart,
          inferIndent(lineStart, text),
          padTop = false
        )
      )
      val importContext: Context =
        if (baseAutoImport.isDefined)
          doLocateImportContext(pos, baseAutoImport)
        else context
      val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
      val owners: scala.collection.Set[Symbol] = this.parentSymbols(context)

      private case class OverrideCandidate(sym: Symbol) {
        val memberType: Type = typed.tpe.memberType(sym)
        val info: Type =
          if (memberType.isErroneous) sym.info
          else {
            memberType match {
              case m: MethodType => m
              case m: NullaryMethodType => m
              case m @ PolyType(_, _: MethodType) => m
              case _ => sym.info
            }
          }

        val history = new ShortenedNames(
          lookupSymbol = { name =>
            context.lookupSymbol(name, _ => true) :: Nil
          },
          config = renameConfig,
          renames = re,
          owners = owners
        )

        val printer = new SignaturePrinter(
          sym,
          history,
          info,
          includeDocs = false,
          includeDefaultParam = false,
          printLongType = false
        )

        val overrideKeyword: String =
          if (!sym.isAbstract || text.startsWith("o", start)) "override "
          // Don't insert `override` keyword if the supermethod is abstract and the
          // user did not explicitly type starting with o . See:
          // https://github.com/scalameta/metals/issues/565#issuecomment-472761240
          else ""

        val lzy: String =
          if (sym.isLazy) "lazy "
          else ""

        val keyword: String =
          if (isVarSetter(sym)) "var "
          else if (sym.isStable) "val "
          else "def "

        val asciOverrideDef: String = {
          if (sym.isAbstract) keyword
          else s"${overrideKeyword}${keyword}"
        }

        val overrideDef: String = metalsConfig.overrideDefFormat() match {
          case OverrideDefFormat.Unicode =>
            if (sym.isAbstract) "🔼 "
            else "⏫ "
          case _ => asciOverrideDef
        }

        val name: String = Identifier(sym.name)

        val filterText: String = s"${overrideKeyword}${lzy}${keyword}${name}"

        // if we had no val or def then filter will be empty
        def toMember = new OverrideDefMember(
          label,
          edit,
          filterText,
          sym,
          history.autoImports(
            pos,
            importContext,
            autoImport.offset,
            autoImport.indent,
            autoImport.padTop
          ),
          details
        )

        private def label = overrideDef + name + signature
        private def details = asciOverrideDef + name + signature
        private def signature = printer.defaultMethodSignature()
        private def edit = new l.TextEdit(
          range,
          if (clientSupportsSnippets) {
            s"$filterText$signature = $${0:???}"
          } else {
            s"$filterText$signature = ???"
          }
        )
      }

      override def contribute: List[Member] = {
        if (start < 0) {
          Nil
        } else {

          val overrideMembers = typed.tpe.members.iterator.toList
            .filter(isOverridableMethod)
            .map(OverrideCandidate.apply)
            .map(_.toMember)

          val overrideDefMembers: List[OverrideDefMember] =
            overrideMembers
              .filter { candidate =>
                CompletionFuzzy.matchesSubCharacters(
                  prefix,
                  candidate.filterText
                )
              }

          val allAbstractMembers = overrideMembers
            .filter(_.sym.isAbstract)

          val (allAbstractEdits, allAbstractImports) =
            allAbstractMembers.foldLeft(
              (List.empty[l.TextEdit], Set.empty[l.TextEdit])
            ) { (editsAndImports, overrideDefMember) =>
              val edits = overrideDefMember.edit :: editsAndImports._1
              val imports = overrideDefMember.autoImports.toSet ++ editsAndImports
                ._2
              (edits, imports)
            }

          if (allAbstractMembers.length > 1 && overrideDefMembers.length > 1) {
            val necessaryIndent = if (metalsConfig.snippetAutoIndent()) {
              ""
            } else {
              val amount =
                allAbstractEdits.head.getRange.getStart.getCharacter
              " " * amount
            }

            val implementAll: TextEditMember = new TextEditMember(
              prefix,
              new l.TextEdit(
                range,
                allAbstractEdits
                  .map(_.getNewText)
                  .mkString(s"\n${necessaryIndent}")
              ),
              completionsSymbol("implement"),
              label = Some("Implement all members"),
              detail = Some(s" (${allAbstractMembers.length} total)"),
              additionalTextEdits = allAbstractImports.toList
            )

            implementAll :: overrideDefMembers
          } else {
            overrideDefMembers
          }
        }
      }
    }

    /**
     * A `match` keyword completion to generate an exhaustive pattern match for sealed types.
     *
     * @param prefix the type of the qualifier being matched.
     */
    case class MatchKeyword(
        prefix: Type,
        editRange: l.Range,
        pos: Position,
        text: String
    ) extends CompletionPosition {
      override def contribute: List[Member] = {
        val tpe = prefix.widen
        val members = ListBuffer.empty[TextEditMember]
        val importPos = autoImportPosition(pos, text)
        val context = doLocateImportContext(pos, importPos)
        val subclasses = ListBuffer.empty[Symbol]

        tpe.typeSymbol.foreachKnownDirectSubClass { sym =>
          subclasses += sym
        }
        val subclassesResult = subclasses.result()

        // sort subclasses by declaration order
        // see: https://github.com/scalameta/metals-feature-requests/issues/49
        val sortedSubclasses =
          if (subclassesResult.forall(_.pos.isDefined)) {
            // if all the symbols of subclasses' position is defined
            // we can sort those symbols by declaration order
            // based on their position information quite cheaply
            subclassesResult.sortBy(subclass =>
              (subclass.pos.line, subclass.pos.column)
            )
          } else {
            // Read all the symbols in the source that contains
            // the definition of the symbol in declaration order
            val defnSymbols = search
              .definitionSourceToplevels(semanticdbSymbol(tpe.typeSymbol))
              .asScala
            if (defnSymbols.length > 0)
              subclassesResult
                .sortBy(sym => {
                  defnSymbols.indexOf(semanticdbSymbol(sym))
                })
            else
              subclassesResult
          }

        sortedSubclasses.foreach { sym =>
          val (shortName, edits) =
            importPos match {
              case Some(value) =>
                ShortenedNames.synthesize(sym, pos, context, value)
              case scala.None =>
                (sym.fullNameSyntax, Nil)
            }
          members += toCaseMember(
            shortName,
            sym,
            sym.dealiasedSingleType,
            context,
            editRange,
            edits,
            isSnippet = false
          )
        }
        val basicMatch = new TextEditMember(
          "match",
          new l.TextEdit(
            editRange,
            if (clientSupportsSnippets) {
              "match {\n\tcase$0\n}"
            } else {
              "match"
            }
          ),
          completionsSymbol("match"),
          label = Some("match"),
          command = metalsConfig.completionCommand().asScala
        )
        val result: List[Member] = members.toList match {
          case Nil => List(basicMatch)
          case head :: tail =>
            val newText = new l.TextEdit(
              editRange,
              tail
                .map(_.edit.getNewText())
                .mkString(
                  if (clientSupportsSnippets) {
                    s"match {\n\t${head.edit.getNewText} $$0\n\t"
                  } else {
                    s"match {\n\t${head.edit.getNewText}\n\t"
                  },
                  "\n\t",
                  "\n}"
                )
            )
            val detail =
              s" ${metalsToLongString(tpe, new ShortenedNames())} (${members.length} cases)"
            val exhaustiveMatch = new TextEditMember(
              "match (exhaustive)",
              newText,
              tpe.typeSymbol,
              label = Some("match (exhaustive)"),
              detail = Some(detail),
              additionalTextEdits =
                members.toList.flatMap(_.additionalTextEdits)
            )
            List(exhaustiveMatch, basicMatch)
        }
        result
      }
    }

    /**
     * A `case` completion showing the valid subtypes of the type being deconstructed.
     *
     * @param selector the match expression being deconstructed or `EmptyTree` when
     *                 not in a match expression (for example `List(1).foreach { case@@ }`.
     * @param editRange the range in the original source file enclosing the `case` keyword being completed.
     *                  Used as the position of the main text edit of the completion.
     * @param pos the position of the completion in the instrumented source file with `_CURSOR_` instrumentation.
     * @param text the text of the original source file without `_CURSOR_`.
     * @param parent the parent tree node of the pattern match, for example `Apply(_, _)` when in
     *               `List(1).foreach { cas@@ }`, used as fallback to compute the type of the selector when
     *               it's `EmptyTree`.
     */
    case class CaseKeyword(
        selector: Tree,
        editRange: l.Range,
        pos: Position,
        text: String,
        parent: Tree
    ) extends CompletionPosition {
      val context: Context = doLocateContext(pos)
      val parents: Parents = selector match {
        case EmptyTree =>
          val typedParent = typedTreeAt(parent.pos)
          typedParent match {
            case Apply(_, Function(params, _) :: Nil) =>
              new Parents(params.map(_.symbol.info))
            case _ =>
              val seenFrom = typedParent match {
                case TreeApply(fun, _)
                    if fun.tpe != null && !fun.tpe.isErroneous =>
                  fun.tpe
                case _ =>
                  metalsSeenFromType(typedParent, typedParent.symbol)
              }
              seenFrom.paramss match {
                case (head :: Nil) :: _
                    if definitions.isFunctionType(head.info) ||
                      definitions.isPartialFunctionType(head.info) =>
                  val argTypes =
                    if (definitions.isPartialFunctionType(head.info)) {
                      head.info.typeArgs.init
                    } else {
                      metalsFunctionArgTypes(head.info)
                    }
                  new Parents(argTypes)
                case _ =>
                  new Parents(NoType)
              }
          }
        case sel => new Parents(sel.pos)
      }
      override def isPrioritized(member: Member): Boolean =
        member.isInstanceOf[TextEditMember]
      override def contribute: List[Member] = {
        val result = ListBuffer.empty[Member]
        val isVisited = mutable.Set.empty[Symbol]
        def visit(
            sym: Symbol,
            name: String,
            autoImports: List[l.TextEdit]
        ): Unit = {
          val fsym = sym.dealiasedSingleType
          val isValid = !isVisited(sym) &&
            !isVisited(fsym) &&
            !parents.isParent(fsym) &&
            (fsym.isCase ||
              fsym.hasModuleFlag ||
              fsym.isInstanceOf[TypeSymbol]) &&
            parents.isSubClass(fsym, includeReverse = false)
          def recordVisit(s: Symbol): Unit = {
            if (s != NoSymbol && !isVisited(s)) {
              isVisited += s
              recordVisit(s.moduleClass)
              recordVisit(s.module)
              recordVisit(s.dealiased)
            }
          }
          if (isValid) {
            recordVisit(sym)
            recordVisit(fsym)
            result += toCaseMember(
              name,
              sym,
              fsym,
              context,
              editRange,
              autoImports
            )
          }
        }

        // Step 1: walk through scope members.
        metalsScopeMembers(pos).iterator
          .foreach(m => visit(m.sym.dealiased, Identifier(m.sym.name), Nil))

        // Step 2: walk through known direct subclasses of sealed types.
        val autoImport = autoImportPosition(pos, text)
        parents.selector.typeSymbol.foreachKnownDirectSubClass { sym =>
          autoImport match {
            case Some(value) =>
              val (shortName, edits) =
                ShortenedNames.synthesize(sym, pos, context, value)
              visit(sym, shortName, edits)
            case scala.None =>
              visit(sym, sym.fullNameSyntax, Nil)
          }
        }

        // Step 3: special handle case when selector is a tuple or `FunctionN`.
        if (definitions.isTupleType(parents.selector)) {
          result += new TextEditMember(
            "case () =>",
            new l.TextEdit(
              editRange,
              if (clientSupportsSnippets) "case ($0) =>" else "case () =>"
            ),
            parents.selector.typeSymbol,
            label = Some(s"case ${parents.selector} =>"),
            command = metalsConfig.parameterHintsCommand().asScala
          )
        }

        result.toList
      }
    }

    def toCaseMember(
        name: String,
        sym: Symbol,
        fsym: Symbol,
        context: Context,
        editRange: l.Range,
        autoImports: List[l.TextEdit],
        isSnippet: Boolean = true
    ): TextEditMember = {
      sym.info // complete
      if (sym.isCase || fsym.hasModuleFlag) {
        // Syntax for deconstructing the symbol as an infix operator, for example `case head :: tail =>`
        val isInfixEligible =
          context.symbolIsInScope(sym) ||
            autoImports.nonEmpty
        val infixPattern: Option[String] =
          if (isInfixEligible &&
            sym.isCase &&
            !Character.isUnicodeIdentifierStart(sym.decodedName.head)) {
            sym.primaryConstructor.paramss match {
              case (a :: b :: Nil) :: _ =>
                Some(
                  s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
                )
              case _ => scala.None
            }
          } else {
            scala.None
          }
        val pattern = infixPattern.getOrElse {
          // Fallback to "apply syntax", example `case ::(head, tail) =>`
          val suffix =
            if (fsym.hasModuleFlag) ""
            else {
              sym.primaryConstructor.paramss match {
                case Nil => "()"
                case head :: _ =>
                  head
                    .map(param => Identifier(param.name))
                    .mkString("(", ", ", ")")
              }
            }
          name + suffix
        }
        val label = s"case $pattern =>"
        new TextEditMember(
          filterText = label,
          edit = new l.TextEdit(
            editRange,
            label + (if (isSnippet && clientSupportsSnippets) " $0" else "")
          ),
          sym = sym,
          label = Some(label),
          additionalTextEdits = autoImports
        )
      } else {
        // Symbol is not a case class with unapply deconstructor so we use typed pattern, example `_: User`
        val suffix = sym.typeParams match {
          case Nil => ""
          case tparams => tparams.map(_ => "_").mkString("[", ", ", "]")
        }
        new TextEditMember(
          s"case _: $name",
          new l.TextEdit(
            editRange,
            if (isSnippet && clientSupportsSnippets)
              s"case $${0:_}: $name$suffix => "
            else s"case _: $name$suffix =>"
          ),
          sym,
          Some(s"case _: $name$suffix =>"),
          additionalTextEdits = autoImports
        )
      }

    }

    case class CasePattern(
        isTyped: Boolean,
        c: CaseDef,
        m: Match
    ) extends CompletionPosition {
      override def contribute: List[Member] = Nil
      override def isCandidate(member: Member): Boolean = {
        // Can't complete regular def methods in pattern matching.
        !member.sym.isMethod || !member.sym.isVal
      }
      val parents = new Parents(m.selector.pos)
      override def isPrioritized(head: Member): Boolean = {
        parents.isSubClass(head.sym, includeReverse = false) || {
          def alternatives(unapply: Symbol): Boolean =
            unapply.alternatives.exists { unapply =>
              unapply.info
              unapply.paramLists match {
                case (param :: Nil) :: Nil =>
                  parents.isSubClass(param, includeReverse = true)
                case _ =>
                  false
              }
            }
          alternatives(head.sym.tpe.member(termNames.unapply)) ||
          alternatives(head.sym.tpe.member(termNames.unapplySeq))
        }
      }
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
      lastVisistedParentTrees = Nil
      traverse(root)
      lastVisistedParentTrees match {
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
              lastVisistedParentTrees ::= t
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

  class Parents(val selector: Type) {
    def this(pos: Position) = this(typedTreeAt(pos).tpe)
    def this(tpes: List[Type]) = this(
      tpes match {
        case Nil => NoType
        case head :: Nil => head
        case _ => definitions.tupleType(tpes)
      }
    )
    val isParent: Set[Symbol] =
      Set(selector.typeSymbol, selector.typeSymbol.companion)
        .filterNot(_ == NoSymbol)
    val isBottom: Set[Symbol] = Set[Symbol](
      definitions.NullClass,
      definitions.NothingClass
    )
    def isSubClass(sym: Symbol, includeReverse: Boolean): Boolean = {
      val typeSymbol = sym.tpe.typeSymbol
      !isBottom(typeSymbol) &&
      isParent.exists { parent =>
        typeSymbol.isSubClass(parent) ||
        (includeReverse && parent.isSubClass(typeSymbol))
      }
    }
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
    def loop(enclosing: List[Tree]): Int = enclosing match {
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
    loop(lastVisistedParentTrees)
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
