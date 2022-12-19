package scala.meta.internal.pc.completions

import java.net.URI

import scala.collection.immutable.Nil
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.Identifier
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait MatchCaseCompletions { this: MetalsGlobal =>

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
  case class CaseKeywordCompletion(
      selector: Tree,
      editRange: l.Range,
      pos: Position,
      text: String,
      parent: Tree,
      patternOnly: Option[String] = None,
      hasBind: Boolean = false
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
      val completionGenerator = new CompletionValueGenerator(
        editRange,
        context,
        clientSupportsSnippets,
        patternOnly,
        hasBind
      )
      val selectorSym = parents.selector.typeSymbol
      val result = ListBuffer.empty[Member]
      val isVisited = mutable.Set.empty[Symbol]
      def visit(
          sym: Symbol,
          name: String,
          autoImports: List[l.TextEdit]
      ): Unit = {
        val fsym = sym.dealiasedSingleType
        def recordVisit(s: Symbol): Unit = {
          if (s != NoSymbol && !isVisited(s)) {
            isVisited += s
            recordVisit(s.moduleClass)
            recordVisit(s.module)
            recordVisit(s.dealiased)
          }
        }
        if (!isVisited(sym) && !isVisited(fsym)) {
          recordVisit(sym)
          recordVisit(fsym)
          if (fuzzyMatches(name))
            result += completionGenerator.toCaseMember(
              name,
              sym,
              fsym,
              autoImports
            )
        }
      }

      // Special handle case when selector is a tuple or `FunctionN`.
      if (definitions.isTupleType(parents.selector)) {
        List(
          new TextEditMember(
            "case () =>",
            new l.TextEdit(
              editRange,
              if (clientSupportsSnippets) "case ($0) =>" else "case () =>"
            ),
            selectorSym,
            label = Some(s"case ${parents.selector} =>"),
            command = metalsConfig.parameterHintsCommand().asScala
          )
        )
      } else {
        // Step 1: walk through scope members.
        metalsScopeMembers(pos).iterator
          .foreach { m =>
            val sym = m.sym.dealiased
            val fsym = sym.dealiasedSingleType
            val isValid = !parents.isParent(fsym) &&
              (fsym.isCase ||
                fsym.hasModuleFlag ||
                fsym.isInstanceOf[TypeSymbol]) &&
              parents.isSubClass(fsym, includeReverse = false)
            if (isValid) visit(sym, Identifier(m.sym.name), Nil)
          }

        // Step 2: walk through known direct subclasses of sealed types.
        val autoImport = autoImportPosition(pos, text)
        selectorSym.foreachKnownDirectSubClass { sym =>
          autoImport match {
            case Some(value) =>
              val (shortName, edits) =
                ShortenedNames.synthesize(sym, pos, context, value)
              visit(sym, shortName, edits)
            case scala.None =>
              visit(sym, sym.fullNameSyntax, Nil)
          }
        }

        result.toList
      }
    }

    def fuzzyMatches(name: String): Boolean =
      patternOnly match {
        case None => true
        case Some(query) => CompletionFuzzy.matches(query, name)
      }
  }

  /**
   * A `match` keyword completion to generate an exhaustive pattern match for sealed types.
   *
   * @param prefix the type of the qualifier being matched.
   */
  case class MatchKeywordCompletion(
      prefix: Type,
      editRange: l.Range,
      pos: Position,
      source: URI,
      text: String
  ) extends CompletionPosition {
    private def subclassesForType(tpe: Type): List[Symbol] = {
      if (tpe.typeSymbol.isRefinementClass) {
        val RefinedType(parents, _) = tpe
        parents.map(t => {
          val subclasses = ListBuffer.empty[Symbol]
          t.typeSymbol.foreachKnownDirectSubClass { sym => subclasses += sym }
          subclasses.result()
        }) match {
          case Nil => Nil
          case subcls => subcls.reduce(_.intersect(_))
        }
      } else {
        val subclasses = ListBuffer.empty[Symbol]
        tpe.typeSymbol.foreachKnownDirectSubClass { sym => subclasses += sym }
        subclasses.result()
      }
    }
    override def contribute: List[Member] = {
      val tpe = prefix.widen.bounds.hi
      val members = ListBuffer.empty[TextEditMember]
      val importPos = autoImportPosition(pos, text)
      val context = doLocateImportContext(pos)
      val completionGenerator = new CompletionValueGenerator(
        editRange,
        context,
        clientSupportsSnippets
      )
      val subclassesResult = subclassesForType(tpe)

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
            .definitionSourceToplevels(semanticdbSymbol(tpe.typeSymbol), source)
            .asScala
          if (defnSymbols.length > 0) {
            val symbolIdx = defnSymbols.zipWithIndex.toMap
            subclassesResult
              .sortBy(sym => {
                symbolIdx.getOrElse(semanticdbSymbol(sym), -1)
              })
          } else {
            subclassesResult
          }
        }

      sortedSubclasses.foreach { sym =>
        val (shortName, edits) =
          importPos match {
            case Some(value) =>
              ShortenedNames.synthesize(sym, pos, context, value)
            case scala.None =>
              (sym.fullNameSyntax, Nil)
          }
        members += completionGenerator.toCaseMember(
          shortName,
          sym,
          sym.dealiasedSingleType,
          edits
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
              .map(_.label.getOrElse(""))
              .mkString(
                if (clientSupportsSnippets) {
                  s"match {\n\t${head.label.getOrElse("")} $$0\n\t"
                } else {
                  s"match {\n\t${head.label.getOrElse("")}\n\t"
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
            additionalTextEdits = members.toList.flatMap(_.additionalTextEdits)
          )
          List(exhaustiveMatch, basicMatch)
      }
      result
    }
  }

  def isMatchPrefix(name: Name): Boolean =
    name.endsWith(CURSOR) &&
      "match".startsWith(name.toString().stripSuffix(CURSOR))

  /**
   * Returns true if the identifier comes after an opening brace character '{'
   */
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

  def isCasePrefix(name: Name): Boolean = {
    val prefix = name.decoded.stripSuffix(CURSOR)
    Set("c", "ca", "cas", "case").contains(prefix)
  }

  class CompletionValueGenerator(
      editRange: l.Range,
      context: Context,
      clientSupportsSnippets: Boolean,
      patternOnly: Option[String] = None,
      hasBind: Boolean = false
  ) {

    def toCaseMember(
        name: String,
        sym: Symbol,
        fsym: Symbol,
        autoImports: List[l.TextEdit]
    ): TextEditMember = {
      sym.info
      val isModuleLike = fsym.hasModuleFlag || fsym.hasJavaEnumFlag
      val pattern =
        if ((sym.isCase || isModuleLike) && !hasBind) {
          val isInfixEligible =
            context.symbolIsInScope(sym) ||
              autoImports.nonEmpty
          if (
            isInfixEligible &&
            sym.isCase &&
            !Character.isUnicodeIdentifierStart(sym.decodedName.head)
          )
            // Deconstructing the symbol as an infix operator, for example `case head :: tail =>`
            tryInfixPattern(sym).getOrElse(
              unapplyPattern(sym, name, isModuleLike)
            )
          else
            unapplyPattern(
              sym,
              name,
              isModuleLike
            ) // Apply syntax, example `case ::(head, tail) =>`
        } else
          typePattern(
            sym,
            name
          ) // Symbol is not a case class with unapply deconstructor so we use typed pattern, example `_: User`

      val label =
        if (patternOnly.isEmpty) s"case $pattern =>"
        else pattern
      val cursorSuffix = (if (patternOnly.nonEmpty) "" else " ") +
        (if (clientSupportsSnippets) "$0" else "")
      new TextEditMember(
        filterText = label,
        edit = new l.TextEdit(
          editRange,
          label + cursorSuffix
        ),
        sym = sym,
        label = Some(label),
        additionalTextEdits = autoImports
      )
    }

    private def tryInfixPattern(sym: Symbol): Option[String] = {
      sym.primaryConstructor.paramss match {
        case (a :: b :: Nil) :: Nil =>
          Some(
            s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
          )
        case _ :: (a :: b :: Nil) :: _ =>
          Some(
            s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
          )
        case _ => None
      }
    }
    private def unapplyPattern(
        sym: Symbol,
        name: String,
        isModuleLike: Boolean
    ): String = {
      val suffix =
        if (isModuleLike) ""
        else
          sym.primaryConstructor.paramss match {
            case Nil => "()"
            case _ :: params :: _ =>
              params
                .map(param => param.name)
                .mkString("(", ", ", ")")
            case head :: _ =>
              head
                .map(param => param.name)
                .mkString("(", ", ", ")")
          }
      name + suffix
    }

    private def typePattern(
        sym: Symbol,
        name: String
    ): String = {
      val suffix = sym.typeParams match {
        case Nil => ""
        case tparams => tparams.map(_ => "_").mkString("[", ", ", "]")
      }
      val bind = if (hasBind) "" else "_: "
      bind + name + suffix
    }
  }

  class Parents(val selector: Type) {
    def this(pos: Position) = this(typedTreeAt(pos).tpe)
    def this(tpes: List[Type]) =
      this(
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

  object CaseExtractors {
    object CaseDefMatch {
      def unapply(path: List[Tree]): Option[(Tree, Tree)] =
        path match {
          case (_: CaseDef) :: (m: Match) :: parent :: _ =>
            Some((m.selector, parent))
          case _ => None
        }
    }

    object CaseExtractor {
      def unapply(path: List[Tree]): Option[(Tree, Tree)] =
        path match {
          // xxx match {
          //   ca@@
          case (m @ Match(_, Nil)) :: parent :: _ =>
            Some((m.selector, parent))

          // xxx match {
          //   case A =>
          //   ca@@
          case (id @ Ident(name)) :: (cd: CaseDef) :: (m: Match) :: parent :: _
              if isCasePrefix(name) &&
                cd.pos.line != id.pos.line =>
            Some((m.selector, parent))

          // xxx match {
          //   case A => ()
          //   ca@@
          case (ident @ Ident(name)) :: Block(
                _,
                expr
              ) :: CaseDefMatch(selector, parent)
              if ident == expr && isCasePrefix(name) =>
            Some((selector, parent))

          case _ => None
        }
    }
    object CasePatternExtractor {
      def unapply(path: List[Tree]): Option[(Tree, Tree, String)] =
        path match {
          // case @@ =>
          case Bind(_, _) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, ""))
          // case Som@@ =>
          case Ident(
                name
              ) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, name.decoded))
          // case abc @ @@ =>
          case Bind(_, _) :: Bind(
                _,
                _
              ) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, ""))
          // case abc @ Som@@ =>
          case Ident(name) :: Bind(
                _,
                _
              ) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, name.decoded))
          case _ => None
        }
    }

    object TypedCasePatternExtractor {
      def unapply(path: List[Tree]): Option[(Tree, Tree, String)] =
        path match {
          // case _: Som@@ =>
          // case _: @@ =>
          case Ident(name) :: Typed(
                _,
                _
              ) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, name.decoded))
          // case ab: Som@@ =>
          // case ab: @@ =>
          case Ident(name) :: Typed(_, _) :: Bind(
                _,
                _
              ) :: CaseDefMatch(selector, parent) =>
            Some((selector, parent, name.decoded))
          case _ => None
        }
    }
  }

}
