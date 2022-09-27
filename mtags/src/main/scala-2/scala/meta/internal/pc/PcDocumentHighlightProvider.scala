package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind

/**
 * @param compiler
 */

final class PcDocumentHighlightProvider(
    compiler: MetalsGlobal
) {
  import compiler._

  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)

  def highlights(
      params: OffsetParams
  ): List[DocumentHighlight] = {

    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )

    val pos = unit.position(params.offset)

    // We need to collect named params since they will not show on fully typed tree
    lazy val namedArgCache = {
      val parsedTree = parseTree(unit.source)
      parsedTree.collect { case arg @ AssignOrNamedArg(_, rhs) =>
        rhs.pos.start -> arg
      }.toMap
    }

    typeCheck(unit)
    val typedTree = locateTree(pos) match {
      // Check actual object if apply is synthetic
      case sel @ Select(qual, name)
          if name == nme.apply && qual.pos == sel.pos =>
        qual
      case Import(expr, _) if expr.pos.includes(pos) =>
        // imports seem to be marked as transparent
        locateTree(pos, expr, acceptTransparent = true)
      case t => t
    }

    /**
     * Find all symbols that should be shown together.
     * For example class we want to also show companion object.
     *
     * @param sym symbol to find the alternative candidates for
     * @return set of possible symbols
     */
    def symbolAlternatives(sym: Symbol) = {
      val all =
        if (sym.isClass)
          Set(sym, sym.companionModule, sym.companion.moduleClass)
        else if (sym.isModuleOrModuleClass)
          Set(sym, sym.companionClass, sym.moduleClass)
        else if (sym.isTerm) {
          val info =
            if (sym.owner.isClass) sym.owner.info
            else sym.owner.owner.info
          Set(
            sym,
            info.member(sym.getterName),
            info.member(sym.setterName),
            info.member(sym.localName)
          )
        } else Set(sym)
      all.filter(s => s != NoSymbol && !s.isError)
    }

    def fallbackSymbol(name: Name, pos: Position) = {
      val context = doLocateImportContext(pos)
      context.lookupSymbol(name, sym => sym.isType) match {
        case LookupSucceeded(_, symbol) =>
          Some(symbol)
        case _ => None
      }
    }

    // First identify the symbol we are at, comments identify @@ as current cursor position
    val soughtSymbols: Option[Set[Symbol]] = typedTree match {
      /* simple identifier:
       * val a = val@@ue + value
       */
      case (id: Ident) =>
        // might happen in type trees
        // also this doesn't seem to be picked up by semanticdb
        if (id.symbol == NoSymbol)
          fallbackSymbol(id.name, pos).map(symbolAlternatives)
        else {
          Some(symbolAlternatives(id.symbol))
        }
      /* named argument, which is a bit complex:
       * foo(nam@@e = "123")
       */
      case (apply: Apply) =>
        apply.args
          .flatMap { arg =>
            namedArgCache.get(arg.pos.start)
          }
          .collectFirst {
            case AssignOrNamedArg(id: Ident, _) if id.pos.includes(pos) =>
              apply.symbol.paramss.flatten.find(_.name == id.name).map { s =>
                // if it's a case class we need to look for parameters also
                if (caseClassSynthetics(s.owner.name) && s.owner.isSynthetic) {
                  val constructorOwner =
                    if (s.owner.owner.isCaseClass) s.owner.owner
                    else s.owner.owner.companion
                  val info = constructorOwner.info
                  val constructorParams = info.members
                    .filter(_.isConstructor)
                    .flatMap(_.paramss)
                    .flatten
                    .toSet
                  (constructorParams ++ Set(
                    s,
                    info.member(s.getterName),
                    info.member(s.setterName),
                    info.member(s.localName)
                  )).filter(_ != NoSymbol)
                } else Set(s)
              }
          }
          .flatten
      /* all definitions:
       * def fo@@o = ???
       * class Fo@@o = ???
       * etc.
       */
      case (df: DefTree) if df.namePos.includes(pos) =>
        Some(symbolAlternatives(df.symbol))
      /* Import selectors:
       * import scala.util.Tr@@y
       */
      case (imp: Import) if imp.pos.includes(pos) =>
        imp.selector(pos).map(symbolAlternatives)
      /* simple selector:
       * object.val@@ue
       */
      case (sel: NameTree) if sel.namePos.includes(pos) =>
        Some(symbolAlternatives(sel.symbol))
      case _ =>
        None
    }

    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    soughtSymbols match {
      case Some(sought) =>
        lazy val owners = sought
          .flatMap(s => symbolAlternatives(s.owner))
          .filter(_ != NoSymbol)
        lazy val soughtNames: Set[Name] = sought.map(_.name)

        /*
         * For comprehensions have two owners, one for the enumerators and one for
         * yield. This is a heuristic to find that out.
         */
        def isForComprehensionOwner(named: NameTree) =
          soughtNames(named.name) &&
            named.symbol.owner.isAnonymousFunction && owners.exists(
              _.pos.point == named.symbol.owner.pos.point
            )

        def traverse(
            highlights: Set[DocumentHighlight],
            tree: Tree
        ): Set[DocumentHighlight] = {
          tree match {
            /**
             * All indentifiers such as:
             * val a = <<b>>
             */
            case ident: Ident
                if ident.pos.isRange &&
                  (sought(ident.symbol) || isForComprehensionOwner(ident)) =>
              highlights + new DocumentHighlight(
                ident.pos.toLsp,
                DocumentHighlightKind.Read
              )

            /**
             * Needed for type trees such as:
             * type A = [<<b>>]
             */
            case tpe: TypeTree
                if tpe.original != null && sought(tpe.original.symbol) &&
                  tpe.pos.isRange =>
              highlights + new DocumentHighlight(
                typePos(tpe).toLsp,
                DocumentHighlightKind.Read
              )
            /**
             * All select statements such as:
             * val a = hello.<<b>>
             */
            case sel: Select if sought(sel.symbol) && sel.pos.isRange =>
              traverse(
                highlights + new DocumentHighlight(
                  sel.namePos.toLsp,
                  DocumentHighlightKind.Read
                ),
                sel.qualifier
              )
            /* all definitions:
             * def <<foo>> = ???
             * class <<Foo>> = ???
             * etc.
             */
            case df: MemberDef
                if df.pos.isRange &&
                  (sought(df.symbol) || isForComprehensionOwner(df)) =>
              (annotationChildren(df) ++ df.children).foldLeft(
                highlights + new DocumentHighlight(
                  df.namePos.toLsp,
                  DocumentHighlightKind.Write
                )
              )(traverse(_, _))
            /* Named parameters, since they don't show up in typed tree:
             * foo(<<name>> = "abc")
             * User(<<name>> = "abc")
             * etc.
             */
            case appl: Apply
                if owners(appl.symbol) || owners(appl.symbol.owner) =>
              val named = appl.args
                .flatMap { arg =>
                  namedArgCache.get(arg.pos.start)
                }
                .collectFirst {
                  case AssignOrNamedArg(i @ Ident(name), _)
                      if (sought.exists(sym => sym.name == name)) =>
                    new DocumentHighlight(
                      i.pos.toLsp,
                      DocumentHighlightKind.Read
                    )
                }
              tree.children.foldLeft(highlights ++ named)(traverse(_, _))

            /**
             * We don't automatically traverser types like:
             * val opt: Option[<<String>>] =
             */
            case tpe: TypeTree if tpe.original != null =>
              tpe.original.children.foldLeft(highlights)(traverse(_, _))
            /**
             * Some type trees don't have symbols attached such as:
             * type A = List[_ <: <<Iterable>>[Int]]
             */
            case id: Ident
                if id.symbol == NoSymbol && soughtNames.exists(_ == id.name) =>
              fallbackSymbol(id.name, id.pos) match {
                case Some(sym) if sought(sym) =>
                  highlights + new DocumentHighlight(
                    id.pos.toLsp,
                    DocumentHighlightKind.Read
                  )
                case _ => highlights
              }

            /**
             * For traversing annotations:
             * @<<JsonNotification>>("")
             * def params() = ???
             */
            case df: MemberDef =>
              (tree.children ++ annotationChildren(df))
                .foldLeft(highlights)(traverse(_, _))

            /**
             * For traversing import selectors:
             * import scala.util.<<Try>>
             */
            case imp: Import
                if owners(imp.expr.symbol) && imp.selectors
                  .exists(sel => soughtNames(sel.name)) =>
              imp.selectors.foldLeft(traverse(highlights, imp.expr)) {
                case (highlights, sel) if soughtNames(sel.name) =>
                  val positions =
                    if (!sel.rename.isEmpty)
                      Set(
                        sel.renamePosition(pos.source),
                        sel.namePosition(pos.source)
                      )
                    else Set(sel.namePosition(pos.source))
                  highlights ++ positions.map(pos =>
                    new DocumentHighlight(pos.toLsp)
                  )
                case (highlights, _) =>
                  highlights
              }
            // catch all missed named trees
            case name: NameTree if sought(name.symbol) && name.pos.isRange =>
              tree.children.foldLeft(
                highlights + new DocumentHighlight(
                  name.namePos.toLsp,
                  DocumentHighlightKind.Read
                )
              )(traverse(_, _))

            case _ =>
              tree.children.foldLeft(highlights)(traverse(_, _))
          }
        }
        val all = traverse(Set.empty[DocumentHighlight], unit.lastBody)
        all.toList.distinct
      case None => Nil
    }
  }

  private def annotationChildren(mdef: MemberDef): List[Tree] = {
    mdef.mods.annotations match {
      case Nil if mdef.symbol != null =>
        // After typechecking, annotations are moved from the modifiers
        // to the annotation on the symbol of the annotatee.
        mdef.symbol.annotations.map(_.original)
      case anns => anns
    }
  }

  private def typePos(tpe: TypeTree) = {
    tpe.original match {
      case AppliedTypeTree(tpt, _) => tpt.pos
      case sel: NameTree => sel.namePos
      case _ => tpe.pos
    }
  }
}
