package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}
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

  class NamedArgMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

  class InterpolatorMember(
      val filterText: String,
      val edit: l.TextEdit,
      sym: Symbol
  ) extends ScopeMember(sym, NoType, true, EmptyTree)

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
      history: ShortenedNames,
      completion: CompletionPosition
  ): Ordering[Member] = new Ordering[Member] {
    val cache = mutable.Map.empty[Symbol, Boolean]
    override def compare(o1: Member, o2: Member): Int = {
      val byCompletion = -java.lang.Boolean.compare(
        cache.getOrElseUpdate(o1.sym, completion.isPrioritized(o1)),
        cache.getOrElseUpdate(o2.sym, completion.isPrioritized(o2))
      )
      if (byCompletion != 0) byCompletion
      else {
        val byRelevance = Integer.compare(
          relevancePenalty(o1, history),
          relevancePenalty(o2, history)
        )
        if (byRelevance != 0) byRelevance
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
              sym.infoString(short).replaceAllLiterally(" <: <?>", "")
            }
        }
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

  var lastEnclosing: List[Tree] = Nil
  sealed abstract class CompletionPosition {
    def isType: Boolean = false
    def isNew: Boolean = false

    /**
     * Returns false if this member should be excluded from completion items.
     */
    def isCandidate(member: Member): Boolean = true

    /**
     * Returns true if this member should be sorted at the top of completion items.
     */
    def isPrioritized(member: Member): Boolean = false

    /**
     * Returns true if this member should be sorted at the top of completion items.
     */
    def contribute: List[Member] = Nil

  }

  def completionPosition(pos: Position, text: String): CompletionPosition = {
    lastEnclosing match {
      case (name: Ident) :: (a: Apply) :: _ =>
        CompletionPosition.Arg(name, a)
      case (name: Ident) :: (_: Select) :: (_: Assign) :: (a: Apply) :: _ =>
        CompletionPosition.Arg(name, a)
      case Ident(_) :: PatternMatch(c, m) =>
        CompletionPosition.Case(isTyped = false, c, m)
      case Ident(_) :: Typed(_, _) :: PatternMatch(c, m) =>
        CompletionPosition.Case(isTyped = true, c, m)
      case (lit @ Literal(Constant(_: String))) :: _ =>
        isPossibleInterpolatorSplice(pos, text) match {
          case Some(i) =>
            CompletionPosition.Interpolator(lit, pos, i, text)
          case _ =>
            CompletionPosition.None
        }
      case _ =>
        inferCompletionPosition(pos, lastEnclosing)
    }
  }
  case class InterpolatorSplice(dollar: Int, name: String, needsBraces: Boolean)
  def isPossibleInterpolatorSplice(
      pos: Position,
      text: String
  ): Option[InterpolatorSplice] = {
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
        InterpolatorSplice(
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
  def inferCompletionPosition(
      pos: Position,
      enclosing: List[Tree]
  ): CompletionPosition =
    enclosing match {
      case (_: Ident | _: Select) :: tail =>
        tail match {
          case (v: ValOrDefDef) :: _ =>
            if (v.tpt.pos.includes(pos)) {
              CompletionPosition.Type
            } else {
              CompletionPosition.None
            }
          case _ =>
            inferCompletionPosition(pos, tail)
        }
      case AppliedTypeTree(_, args) :: _ =>
        if (args.exists(_.pos.includes(pos))) {
          CompletionPosition.Type
        } else {
          CompletionPosition.None
        }
      case New(_) :: _ =>
        CompletionPosition.New
      case _ =>
        CompletionPosition.None
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
    case class Interpolator(
        lit: Literal,
        pos: Position,
        interpolator: InterpolatorSplice,
        text: String
    ) extends CompletionPosition {
      val offset = if (lit.pos.focusEnd.line == pos.line) CURSOR.length else 0
      val litpos = lit.pos.withEnd(lit.pos.end - offset)
      val lrange = litpos.toLSP
      def write(out: StringBuilder, from: Int, to: Int): Unit = {
        var i = from
        while (i < to) {
          text.charAt(i) match {
            case '$' =>
              out.append("\\$\\$")
            case ch =>
              out.append(ch)
          }
          i += 1
        }
      }
      def newText(sym: Symbol): String = {
        val out = new StringBuilder()
        out.append("s")
        write(out, lit.pos.start, interpolator.dollar)
        // Escape `$` for
        out.append("\\$")
        val symbolName = sym.decodedName.trim
        val identifier = Identifier.backtickWrap(symbolName)
        val symbolNeedsBraces =
          interpolator.needsBraces ||
            identifier.startsWith("`") ||
            sym.isNonNullaryMethod
        if (symbolNeedsBraces) {
          out.append('{')
        }
        out.append(identifier)
        val snippet = sym.paramss match {
          case Nil =>
            "$0"
          case Nil :: Nil =>
            "()$0"
          case _ =>
            "($0)"
        }
        out.append(snippet)
        if (symbolNeedsBraces) {
          out.append('}')
        }
        write(out, pos.point, lit.pos.end - CURSOR.length)
        out.toString()
      }
      val filterText = text.substring(lit.pos.start, pos.point)
      override def contribute: List[Member] = {
        metalsScopeMembers(pos).collect {
          case s: ScopeMember
              if CompletionFuzzy.matches(interpolator.name, s.sym.name) =>
            val edit = new l.TextEdit(lrange, newText(s.sym))
            new InterpolatorMember(filterText, edit, s.sym)
        }
      }
    }
    case object None extends CompletionPosition
    case class Arg(ident: Ident, apply: Apply) extends CompletionPosition {
      val method = typedTreeAt(apply.fun.pos).symbol
      val params: List[Symbol] = {
        def curriedParamList(t: Tree): Int = t match {
          case Apply(fun, _) => 1 + curriedParamList(fun)
          case _ => 0
        }
        val index = curriedParamList(apply.fun)
        method.paramss.lift(index) match {
          case Some(value) => value
          case scala.None => method.paramss.flatten
        }
      }
      val isNamed = apply.args.iterator
        .filterNot(_ == ident)
        .zip(params.iterator)
        .map {
          case (AssignOrNamedArg(Ident(name), _), _) =>
            name
          case (_, param) =>
            param.name
        }
        .toSet
      override def isCandidate(member: Member): Boolean = true
      override def isPrioritized(member: Member): Boolean = true
      override def contribute: List[Member] = {
        val prefix = ident.name.toString.stripSuffix(CURSOR)
        params.iterator
          .filterNot { param =>
            isNamed(param.name) ||
            param.name.containsChar('$') // exclude synthetic parameters
          }
          .filter(param => param.name.startsWith(prefix))
          .map(param => new NamedArgMember(param))
          .toList
      }
    }
    case class Case(
        isTyped: Boolean,
        c: CaseDef,
        m: Match
    ) extends CompletionPosition {
      override def contribute: List[Member] = Nil
      override def isCandidate(member: Member): Boolean = {
        // Can't complete regular def methods in pattern matching.
        !member.sym.isMethod || !member.sym.isVal
      }
      val selector = typedTreeAt(m.selector.pos).tpe
      val parents = Set(selector.typeSymbol, selector.typeSymbol.companion)
      def isSubClass(sym: Symbol, includeReverse: Boolean): Boolean = {
        val typeSymbol = sym.tpe.typeSymbol
        parents.exists { parent =>
          typeSymbol.isSubClass(parent) ||
          (includeReverse && parent.isSubClass(typeSymbol))
        }
      }
      override def isPrioritized(head: Member): Boolean = {
        isSubClass(head.sym, includeReverse = false) || {
          def alternatives(unapply: Symbol): Boolean =
            unapply.alternatives.exists { unapply =>
              unapply.info
              unapply.paramLists match {
                case (param :: Nil) :: Nil =>
                  isSubClass(param, includeReverse = true)
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

  object PatternMatch {
    def unapply(enclosing: List[Tree]): Option[(CaseDef, Match)] =
      enclosing match {
        case (c: CaseDef) :: (m: Match) :: _ =>
          Some((c, m))
        case _ =>
          None
      }
  }
  class MetalsLocator(pos: Position) extends Traverser {
    def locateIn(root: Tree): Tree = {
      lastEnclosing = Nil
      traverse(root)
      lastEnclosing match {
        case head :: _ => head
        case _ => EmptyTree
      }
    }
    protected def isEligible(t: Tree): Boolean = !t.pos.isTransparent
    override def traverse(t: Tree) {
      t match {
        case tt: TypeTree
            if tt.original != null && (tt.pos includes tt.original.pos) =>
          traverse(tt.original)
        case _ =>
          if (t.pos includes pos) {
            if (isEligible(t)) {
              lastEnclosing ::= t
            }
            super.traverse(t)
          } else
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
