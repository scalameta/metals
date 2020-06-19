package scala.meta.internal.pc.completions

import scala.collection.immutable.Nil
import scala.collection.mutable

import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.Identifier
import scala.meta.internal.pc.MetalsGlobal
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

import org.eclipse.{lsp4j => l}

trait OverrideCompletions { this: MetalsGlobal =>

  private val DefaultIndent = 2

  class OverrideDefMember(
      val label: String,
      val edit: l.TextEdit,
      val filterText: String,
      sym: Symbol,
      val autoImports: List[l.TextEdit],
      val detail: String
  ) extends ScopeMember(sym, NoType, true, EmptyTree)

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
  case class OverrideCompletion(
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

    override def contribute: List[Member] = {
      if (start < 0) {
        Nil
      } else {
        val overrideMembers = getMembers(
          typed,
          range,
          pos,
          text,
          text.startsWith("o", start),
          true,
          isCandidate
        )

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

        val (allAbstractEdits, allAbstractImports) = toEdits(allAbstractMembers)

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
                .mkString("", s"\n\n${necessaryIndent}", "\n")
            ),
            completionsSymbol("implement"),
            label = Some("Implement all members"),
            detail = Some(s" (${allAbstractEdits.length} total)"),
            additionalTextEdits = allAbstractImports.toList
          )

          implementAll :: overrideDefMembers
        } else {
          overrideDefMembers
        }
      }
    }
  }

  private def getMembers(
      typed: Tree,
      range: l.Range,
      pos: Position,
      text: String,
      shouldAddOverrideKwd: Boolean,
      shouldMoveCursor: Boolean,
      isCandidate: Symbol => Boolean
  ): List[OverrideDefMember] = {

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
          parent.parentSymbols.foreach { parent => expandParent(parent) }
        }
      }

      while (cx != NoContext && !cx.owner.hasPackageFlag) {
        expandParent(cx.owner)
        cx = cx.outer
      }
      isVisited
    }

    val lineStart: RunId = pos.source.lineToOffset(pos.line - 1)
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
    val owners: scala.collection.Set[Symbol] = parentSymbols(context)

    val isDecl: Set[Symbol] = typed.tpe.decls.toSet
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

    case class OverrideCandidate(sym: Symbol) {
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
        lookupSymbol = { name => context.lookupSymbol(name, _ => true) :: Nil },
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
        if (!sym.isAbstract || shouldAddOverrideKwd) "override "
        // Don't insert `override` keyword if the supermethod is abstract and the
        // user did not explicitly type starting with o . See:
        // https://github.com/scalameta/metals/issues/565#issuecomment-472761240
        else ""

      val lzy: String =
        if (sym.isLazy) "lazy "
        else ""

      val _modifs =
        sym.flagString.replace(
          sym.privateWithin.toString(),
          sym.privateWithin.name.toString()
        )

      val modifs =
        if (_modifs.isEmpty) ""
        else _modifs + " "

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
      val insertText: String =
        s"${overrideKeyword}${modifs}${keyword}${name}$signature"

      // if we had no val or def then filter will be empty
      def toMember =
        new OverrideDefMember(
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
      private def edit =
        new l.TextEdit(
          range,
          if (clientSupportsSnippets && shouldMoveCursor) {
            s"$insertText = $${0:???}"
          } else {
            s"$insertText = ???"
          }
        )
    }

    typed.tpe.members.iterator.toList
      .filter(isOverridableMethod)
      .map(OverrideCandidate.apply)
      .map(_.toMember)
  }

  private def toEdits(
      allAbstractMembers: List[OverrideDefMember]
  ): (List[l.TextEdit], Set[l.TextEdit]) = {
    allAbstractMembers.foldLeft(
      (List.empty[l.TextEdit], Set.empty[l.TextEdit])
    ) { (editsAndImports, overrideDefMember) =>
      val edits = overrideDefMember.edit :: editsAndImports._1
      val imports = overrideDefMember.autoImports.toSet ++ editsAndImports
        ._2
      (edits, imports)
    }
  }

  // NOTE(gabro): sym.isVar does not work consistently across Scala versions
  // Specifically, it behaves differently between 2.11 and 2.12/2.13
  // This check is borrowed from
  // https://github.com/scala/scala/blob/f389823ef0416612a0058a80c1fe85948ff5fc0a/src/reflect/scala/reflect/internal/Symbols.scala#L2645
  private def isVarSetter(sym: Symbol): Boolean =
    !sym.isStable && !sym.isLazy && sym.isAccessor

  def implementAllAt(pos: Position, text: String): List[l.TextEdit] = {

    def implementAllFor(
        t: Template
    ): List[l.TextEdit] = {
      val typed = typedTreeAt(t.pos)
      implementAll(
        typed,
        inferEditPosition(text, t).toLSP,
        t,
        text,
        true,
        _ => true
      )
    }

    // make sure the compilation unit is loaded
    typedTreeAt(pos)

    lastVisitedParentTrees match {

      // class Foo extends Bar {}
      // ~~~~~~~~~~~~~~~~~~~~~~~~
      case (c: ClassDef) :: _ =>
        val t = c.impl
        implementAllFor(t)

      // object Foo extends Bar {}
      // ~~~~~~~~~~~~~~~~~~~~~~~~
      case (m: ModuleDef) :: _ =>
        val t = m.impl
        implementAllFor(t)

      // new Foo {}
      //     ~~~~~~
      case (_: Ident) ::
          (t: Template) :: _ =>
        implementAllFor(t)

      // new Foo[T] {}
      //     ~~~~~~~~~
      case (_: Ident) ::
          (_: AppliedTypeTree) ::
          (t: Template) :: _ =>
        implementAllFor(t)

      case _ =>
        Nil
    }
  }

  /**
   * Get text edits for an `override def` completion to implement methods from the supertype.
   *
   * @param typed the typed tree: template for the class/object we are implementing.
   * @param range the position to fill the completions.
   * @param t the enclosing template for the class/object we are implementing.
   * @param text the text of the original source code.
   * @param shouldAddOverrideKwd if it's true, completion add `override` for each methods.
   * @param isCandidate the determination of whether the symbol will be a possible completion item.
   * @return the list of TextEdit of both method implementations and auto imports.
   */
  private def implementAll(
      typed: Tree,
      range: l.Range,
      t: Template,
      text: String,
      shouldAddOverrideKwd: Boolean,
      isCandidate: Symbol => Boolean
  ): List[l.TextEdit] = {
    val overrideMembers = getMembers(
      typed,
      range,
      t.pos,
      text,
      true,
      false,
      isCandidate
    )

    val allAbstractMembers = overrideMembers
      .filter(_.sym.isAbstract)

    val (allAbstractEdits, allAbstractImports) = toEdits(allAbstractMembers)

    if (allAbstractEdits.length > 0) {

      // infer necessary indent
      //
      // |object Test {
      // |    class Foo extends Bar {} // inferred to 4
      // |}
      val lineStart = t.pos.source.lineToOffset(t.pos.line - 1)
      val necessaryIndent = inferIndent(lineStart, text)

      // infer indent for implementations
      // if there's declaration in the class/object, follow its indent.
      // otherwise the indent default to 2
      val indent = typed.tpe.decls
        .filter(sym =>
          !sym.isSynthetic &&
            !sym.isPrimaryConstructor &&
            sym.pos.line != t.pos.line // filter out explicit primary constructor `class Foo(x: Int) ...`
        )
        .headOption
        .map(existing => {
          " " * inferIndent(
            t.pos.source.lineToOffset(existing.pos.line - 1),
            text
          )
        })
        .getOrElse {
          " " * (necessaryIndent + DefaultIndent)
        }

      val shouldCompleteBraces = hasBody(text, t).isEmpty

      // if the both opening/closing braces located in a line:
      // ```
      // object {
      //   class Foo extends Bar {}
      // }
      // ```
      // or there's no body like this `class Foo extends Bar`.
      // Add an newline and indent in the end of implementations, so that
      // the closing brace is indented.
      //
      // object {
      //   class Foo extends Bar {
      //     override def foo = ???
      //   }
      // }
      val lastIndent =
        if (
          t.pos.source.offsetToLine(t.pos.start) ==
            t.pos.source.offsetToLine(t.pos.end) || shouldCompleteBraces
        )
          "\n" + " " * necessaryIndent
        else ""

      // Add opening/closing braces
      // `object Foo extends Bar` to
      // ```
      // object Foo extends Bar {
      //   override def method: Int = ???
      // }
      // ```
      val start =
        if (shouldCompleteBraces) s" {\n\n${indent}" else s"\n\n${indent}"
      val end =
        if (shouldCompleteBraces) s"\n${lastIndent}}" else s"\n${lastIndent}"
      val implementAll = new l.TextEdit(
        range,
        allAbstractEdits
          .map(_.getNewText)
          .mkString(
            start,
            s"\n\n${indent}",
            end
          )
      )
      implementAll :: allAbstractImports.toList
    } else {
      Nil
    }
  }

  /**
   * Get the position to insert implements for the given Template.
   * `class Foo extends Bar {}` => retuning position would be right after the opening brace.
   * `class Foo extends Bar` => retuning position would be right after `Bar`.
   *
   * @param text the text of the original source code.
   * @param t the enclosing template for the class/object/trait we are implementing.
   */
  private def inferEditPosition(text: String, t: Template): Position = {
    hasBody(text, t)
      .map { offset => t.pos.withStart(offset + 1).withEnd(offset + 1) }
      .getOrElse(
        t.pos.withStart(t.pos.end)
      )
  }

  /**
   * Check if the given Template has body or not:
   * `class Foo extends Bar {}` => Some(position of `{`)
   * `class Foo extends Bar` => None
   *
   * @param text the text of the original source code.
   * @param t the enclosing template for the class/object/trait we are implementing.
   * @return if the given Template has body, returns the pos of opening brace, otherwise returns None
   */
  private def hasBody(text: String, t: Template): Option[Int] = {
    val start = t.pos.start
    val end = t.pos.end
    val offset = text.indexOf('{', start)
    if (offset > 0 && offset < t.pos.end) Some(offset)
    else None
  }
}
