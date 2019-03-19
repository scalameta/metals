package scala.meta.internal.pc

import java.lang.StringBuilder
import org.eclipse.{lsp4j => l}
import scala.meta.internal.semanticdb.Scala._
import scala.collection.mutable
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.util.control.NonFatal
import scala.collection.JavaConverters._

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

  class TextEditMember(
      val filterText: String,
      val edit: l.TextEdit,
      sym: Symbol,
      val label: Option[String] = None,
      val command: Option[String] = None
  ) extends ScopeMember(sym, NoType, true, EmptyTree)

  class OverrideDefMember(
      val label: String,
      val edit: l.TextEdit,
      val filterText: String,
      sym: Symbol,
      val autoImports: List[l.TextEdit]
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

  lazy val isEvilMethod = Set[Name](
    termNames.notifyAll_,
    termNames.notify_,
    termNames.wait_,
    termNames.clone_,
    termNames.finalize_
  )

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
        new SignaturePrinter(m, history, info, includeDocs = false)
          .defaultMethodSignature()
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
        CompletionPosition.Arg(name, a, pos, text)
      case (name: Ident) :: (_: Select) :: (_: Assign) :: (a: Apply) :: _ =>
        CompletionPosition.Arg(name, a, pos, text)
      case Ident(_) :: PatternMatch(c, m) =>
        CompletionPosition.Case(isTyped = false, c, m)
      case Ident(_) :: Typed(_, _) :: PatternMatch(c, m) =>
        CompletionPosition.Case(isTyped = true, c, m)
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
        CompletionPosition.Override(defdef.name, t, pos, text, defdef)
      case (valdef @ ValDef(_, name, _, Literal(Constant(null)))) ::
            (t: Template) :: _ if name.endsWith(CURSOR) =>
        CompletionPosition.Override(name, t, pos, text, valdef)
      case _ =>
        inferCompletionPosition(pos, lastEnclosing)
    }
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
     * A completion to select type members inside string interpolators.
     *
     * Example: {{{
     *   // before
     *   s"Hello $name.len@@!"
     *   // after
     *   s"Hello ${name.length()$0}"
     * }}}
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
      val pos = ident.pos.withEnd(cursor.point).toLSP
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
      val filter =
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
        val symbolName = sym.getterName.decoded
        val identifier = Identifier.backtickWrap(symbolName)
        val symbolNeedsBraces =
          interpolator.needsBraces ||
            identifier.startsWith("`") ||
            sym.isNonNullaryMethod
        if (symbolNeedsBraces) {
          out.append('{')
        }
        out.append(identifier)
        out.append(sym.snippetCursor)
        if (symbolNeedsBraces) {
          out.append('}')
        }
        write(out, pos.point, lit.pos.end - CURSOR.length)
        out.toString
      }
      val filter =
        text.substring(lit.pos.start, pos.point - interpolator.name.length)
      override def contribute: List[Member] = {
        metalsScopeMembers(pos).collect {
          case s: ScopeMember
              if CompletionFuzzy.matches(interpolator.name, s.sym.name) =>
            val edit = new l.TextEdit(lrange, newText(s.sym))
            val filterText = filter + s.sym.name.decoded
            new TextEditMember(filterText, edit, s.sym)
        }
      }
    }
    case object None extends CompletionPosition
    case class Arg(ident: Ident, apply: Apply, pos: Position, text: String)
        extends CompletionPosition {
      val openDelim: Int = {
        var start = ident.pos.start - 1
        while (start > 0 && text.charAt(start).isWhitespace) {
          start -= 1
        }
        start
      }
      val isBrace = text.charAt(openDelim) == '{'
      val method = typedTreeAt(apply.fun.pos)
      val methodSym = method.symbol
      lazy val params: List[Symbol] = {
        def curriedParamList(t: Tree): Int = t match {
          case Apply(fun, _) => 1 + curriedParamList(fun)
          case _ => 0
        }
        val index = curriedParamList(apply.fun)
        method.tpe.paramss.lift(index) match {
          case Some(value) => value
          case scala.None => methodSym.paramss.flatten
        }
      }
      lazy val isNamed = apply.args.iterator
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
      def namedArgMembers: List[Member] = {
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
      def caseTupleMember: List[Member] = {
        for {
          param <- params.headOption.toList
          tuple <- definitions.functionOrSamArgTypes(param.info) match {
            case tuple :: Nil if definitions.isTupleType(tuple) =>
              tuple :: Nil
            case _ =>
              Nil
          }
        } yield {
          val edit = ident.pos.withEnd(pos.point).toLSP
          val history = new ShortenedNames()
          val prettyTuple = metalsToLongString(tuple, history)
          new TextEditMember(
            filterText = "case",
            edit = new l.TextEdit(edit, "case ($0) =>"),
            sym = param,
            label = Some(s"case ${prettyTuple} =>"),
            command = metalsConfig.parameterHintsCommand().asScala
          )
        }
      }
      override def contribute: List[Member] = {
        if (isBrace) caseTupleMember
        else namedArgMembers
      }
    }

    /**
     * An `override def` completion to implement methods from the supertype.
     *
     * @param name the name of the method being completed including the `_CURSOR_` suffix.
     * @param t the enclosing template for the class/object/trait we are implementing.
     * @param pos the position of the completion request, points to `_CURSOR_`.
     * @param text the text of the original source code without `_CURSOR_`.
     * @param defn the method (either `val` or `def`) that we are implementing.
     */
    case class Override(
        name: Name,
        t: Template,
        pos: Position,
        text: String,
        defn: ValOrDefDef
    ) extends CompletionPosition {
      val prefix = name.toString.stripSuffix(CURSOR)
      val typed = typedTreeAt(t.pos)
      val isDecl = typed.tpe.decls.toSet
      val keyword = defn match {
        case _: DefDef => "def"
        case _ => "val"
      }
      val OVERRIDE = " override"
      val start: Int = {
        val fromDef = text.lastIndexOf(s" $keyword ", pos.point)
        if (fromDef > 0 && text.endsWithAt(OVERRIDE, fromDef)) {
          fromDef - OVERRIDE.length()
        } else {
          fromDef
        }
      }

      def isExplicitOverride = text.startsWith(OVERRIDE, start)

      val editStart = start + 1
      val range = pos.withStart(editStart).withEnd(pos.point).toLSP
      val lineStart = pos.source.lineToOffset(pos.line - 1)

      // Infers the indentation at the completion position by counting the number of leading
      // spaces in the line.
      // For example:
      // class Main {
      //   def foo<COMPLETE> // inferred indent is 2 spaces.
      // }
      def inferIndent: Int = {
        var i = 0
        while (lineStart + i < text.length && text.charAt(lineStart + i) == ' ') {
          i += 1
        }
        i
      }

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

      // Returns true if this symbol is a method that we can override.
      def isOverridableMethod(sym: Symbol): Boolean = {
        sym.isMethod &&
        !isDecl(sym) &&
        !isNotOverridableName(sym.name) &&
        sym.name.startsWith(prefix) &&
        !sym.isPrivate &&
        !sym.isSynthetic &&
        !sym.isArtifact &&
        !sym.isEffectivelyFinal &&
        !sym.isVal &&
        !sym.name.endsWith(CURSOR) &&
        !sym.isConstructor &&
        !sym.isMutable &&
        !sym.isSetter && {
          defn match {
            case _: ValDef =>
              // Is this a `override val`?
              sym.isGetter && sym.isStable
            case _ =>
              // It's an `override def`.
              !sym.isGetter
          }
        }
      }
      val context = doLocateContext(pos)
      val re = renamedSymbols(context)
      val owners = this.parentSymbols(context)
      val filter = text.substring(editStart, pos.point - prefix.length)

      def toOverrideMember(sym: Symbol): OverrideDefMember = {
        val memberType = typed.tpe.memberType(sym)
        val info =
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
            context.lookupSymbol(name, _ => true)
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
        val label = printer.defaultMethodSignature(Identifier(sym.name))
        val prefix =
          if (sym.isAbstract) s"${keyword} "
          else s"override ${keyword} "
        val overrideKeyword =
          if (!sym.isAbstract || isExplicitOverride) "override "
          // Don't insert `override` keyword if the supermethod is abstract and the
          // user did not explicitly type "override". See:
          // https://github.com/scalameta/metals/issues/565#issuecomment-472761240
          else ""
        val lzy =
          if (sym.isLazy) "lazy "
          else ""
        val edit = new l.TextEdit(
          range,
          s"${overrideKeyword}${lzy}${keyword} $label = $${0:???}"
        )
        new OverrideDefMember(
          prefix + label,
          edit,
          filter + sym.name.decoded,
          sym,
          history.autoImports(pos, context, lineStart, inferIndent)
        )
      }

      override def contribute: List[Member] = {
        if (start < 0) Nil
        else {
          typed.tpe.members.iterator
            .filter(isOverridableMethod)
            .map(toOverrideMember)
            .toList
        }
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

  lazy val isUninterestingSymbol: Set[Symbol] = Set[Symbol](
    // the methods == != ## are arguably "interesting" but they're here becuase
    // - they're short so completing them doesn't save you keystrokes
    // - they're available on everything so you
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
    definitions.getMemberMethod(
      definitions.getMemberClass(
        definitions.PredefModule,
        TypeName("ArrowAssoc")
      ),
      TermName("→").encode
    ),
    // NOTE(olafur) IntelliJ does not complete the root package and without this filter
    // then `_root_` would appear as a completion result in the code `foobar(_<COMPLETE>)`
    rootMirror.RootPackage
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
}
