package scala.meta.internal.pc.completions

import scala.collection.immutable.Nil
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
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

  /**
   * A `match` keyword completion to generate an exhaustive pattern match for sealed types.
   *
   * @param prefix the type of the qualifier being matched.
   */
  case class MatchKeywordCompletion(
      prefix: Type,
      editRange: l.Range,
      pos: Position,
      text: String
  ) extends CompletionPosition {
    private def subclassesForType(tpe: Type): List[Symbol] = {
      val subclasses = ListBuffer.empty[Symbol]
      if (tpe.typeSymbol.isRefinementClass) {
        val RefinedType(parents, _) = tpe
        parents.foreach(t =>
          t.typeSymbol.foreachKnownDirectSubClass { sym => subclasses += sym }
        )
      } else {
        tpe.typeSymbol.foreachKnownDirectSubClass { sym => subclasses += sym }
      }
      subclasses.result()
    }
    override def contribute: List[Member] = {
      val tpe = prefix.widen.bounds.hi
      val members = ListBuffer.empty[TextEditMember]
      val importPos = autoImportPosition(pos, text)
      val context = doLocateImportContext(pos, importPos)
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
            .definitionSourceToplevels(semanticdbSymbol(tpe.typeSymbol))
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
            additionalTextEdits = members.toList.flatMap(_.additionalTextEdits)
          )
          List(exhaustiveMatch, basicMatch)
      }
      result
    }
  }

  case class CasePatternCompletion(
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

  def isMatchPrefix(name: Name): Boolean =
    name.endsWith(CURSOR) &&
      "match".startsWith(name.toString().stripSuffix(CURSOR))

  /**
   * Returns true if the identifier comes after an opening brace character '{' */
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

  private def toCaseMember(
      name: String,
      sym: Symbol,
      fsym: Symbol,
      context: Context,
      editRange: l.Range,
      autoImports: List[l.TextEdit],
      isSnippet: Boolean = true
  ): TextEditMember = {
    sym.info // complete
    val isModuleLike = fsym.hasModuleFlag || fsym.hasJavaEnumFlag
    if (sym.isCase || isModuleLike) {
      // Syntax for deconstructing the symbol as an infix operator, for example `case head :: tail =>`
      val isInfixEligible =
        context.symbolIsInScope(sym) ||
          autoImports.nonEmpty
      val infixPattern: Option[String] =
        if (
          isInfixEligible &&
          sym.isCase &&
          !Character.isUnicodeIdentifierStart(sym.decodedName.head)
        ) {
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
          if (isModuleLike) ""
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

}
