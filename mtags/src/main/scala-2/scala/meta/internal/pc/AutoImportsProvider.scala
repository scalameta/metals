package scala.meta.internal.pc

import java.util.Optional
import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.PcQueryContext
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class AutoImportsProvider(
    val compiler: MetalsGlobal,
    name: String,
    params: OffsetParams
)(implicit queryInfo: PcQueryContext) {
  import compiler._

  def autoImports(): List[AutoImportsResult] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = Some(params.offset())
    )
    val pos = unit.position(params.offset)
    // macros might break it, see https://github.com/scalameta/metals/issues/2006
    val shouldApplyNameEdit =
      if (pos.start + name.length() < params.text().length()) {
        val foundName =
          params.text().substring(pos.start, pos.start + name.length())
        foundName == name
      } else false

    // make sure the compilation unit is loaded
    typedTreeAt(pos)

    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos)
    val isSeen = mutable.Set.empty[String]
    // a symbol together with the package object class it is importable
    // through, if it is not importable through its own owner (issue #2583)
    val symbols = List.newBuilder[(Symbol, Option[Symbol])]

    // Symbols are keyed on the import that would be written for them, so a
    // type and a term of the same name collapse into one candidate the way
    // a class and its companion object already do.
    def visitThrough(
        sym: Symbol,
        throughPackageObject: Option[Symbol]
    ): Unit = {
      val importPath = throughPackageObject match {
        case Some(pkgClass) => s"${pkgClass.fullName}.${sym.name.decoded}"
        case None => sym.fullName
      }
      if (isSeen.add(importPath)) {
        // the declared owner of a member exposed by a package object is a
        // mixin parent or the package object itself, and neither is a usable
        // import path, so keep the classfile search from offering the same
        // symbol again through its owner
        if (throughPackageObject.isDefined) {
          isSeen += sym.fullName
        }
        symbols += ((sym, throughPackageObject))
      }
    }

    def visit(sym: Symbol): Boolean = {
      visitThrough(sym, None)
      // the search visitors count a `true` as one added symbol, and this
      // provider has always reported none, which makes short queries retry
      false
    }

    compiler.searchOutline(visit, name)

    // symbols importable only through a package object (issue #2583) are
    // searched before the classfile-based sources so that the import a
    // library curates through its package object is offered before the
    // definition's own package
    compiler.searchPackageObjectMembers(
      name,
      context,
      (sym, pkgClass) => visitThrough(sym, Some(pkgClass)),
      () => params.token().isCanceled()
    )

    val visitor =
      new CompilerSearchVisitor(context, visit)
    search.search(name, buildTargetIdentifier, ju.Optional.empty(), visitor)

    def isInImportTree: Boolean = lastVisitedParentTrees match {
      case (_: Import) :: _ => true
      case _ => false
    }

    def correctInTreeContext(sym: Symbol) = lastVisitedParentTrees match {
      case (_: Ident) :: (sel: Select) :: _ =>
        sym.info.members.exists(_.name == sel.name)
      case (_: Ident) :: (_: Apply) :: _ if !sym.isMethod =>
        def applyInObject =
          sym.companionModule.info.members.exists(_.name == nme.apply)
        def applyInClass = sym.info.members.exists(_.name == nme.apply)
        applyInClass || applyInObject
      case (_: Ident) :: SingletonTypeTree(_) :: _ =>
        sym.isModuleOrModuleClass || sym.companionModule != NoSymbol
      case (id: Ident) :: (df: ValOrDefDef) :: _ if df.tpt == id =>
        !sym.isModuleOrModuleClass || sym.companionClass != NoSymbol
      case (_: Ident) :: (_: TypTree) :: _ =>
        !sym.isModuleOrModuleClass || sym.companionClass != NoSymbol
      case _ =>
        true
    }

    def namePos: l.Range =
      pos.withEnd(pos.start + name.length()).toLsp

    def isExactMatch(sym: Symbol, name: String): Boolean =
      sym.name.dropLocal.decoded == name

    def renderResult(
        sym: Symbol,
        throughPackageObject: Option[Symbol]
    ): (AutoImportsResult, Symbol) = {
      val importOwner = throughPackageObject.getOrElse(sym.owner)
      // a member of a package object is imported through the enclosing
      // package, never through `<package>.package`, and `fullNameSyntax`
      // is the helper that already skips package objects and escapes
      // keyword segments
      val pkg = importOwner.fullNameSyntax
      val importOwnerOverride =
        throughPackageObject
          .map(pkgClass => Map(sym -> pkgClass))
          .getOrElse(Map.empty[Symbol, Symbol])
      val edits = importPosition match {
        // if we are in import section just specify full name
        case None if isInImportTree =>
          val fullName =
            if (throughPackageObject.isDefined)
              s"$pkg.${Identifier(sym.name)}"
            else sym.fullNameSyntax
          List(new l.TextEdit(namePos, fullName))
        case None =>
          // No import position means we can't insert an import without clashing with
          // existing symbols in scope, so we just do nothing
          Nil
        case Some(value) =>
          val (short, edits) = ShortenedNames.synthesize(
            TypeRef(ThisType(importOwner), sym, Nil),
            pos,
            context,
            value,
            importOwnerOverride
          )
          val nameEdit = new l.TextEdit(namePos, short)

          if (short != name && shouldApplyNameEdit) {
            nameEdit :: edits
          } else {
            edits
          }
      }
      if (edits.isEmpty) {
        val trees = lastVisitedParentTrees
          .take(5)
          .map(_.getClass().getName())
          .mkString(",")
        logger.warning(
          s"Could not infer edits for $pkg, tree around the position were $trees, auto import position was ${importPosition}"
        )
      }
      (
        AutoImportsResultImpl(
          pkg,
          edits.asJava,
          Optional.of(semanticdbSymbol(sym))
        ),
        sym
      )
    }

    val all = symbols.result().collect {
      case (sym, throughPackageObject)
          if isExactMatch(sym, name) && context.isAccessible(
            sym,
            sym.info
          ) && !sym.owner.isEmptyPackageClass =>
        renderResult(sym, throughPackageObject)
    }

    all match {
      case (onlyResult, _) :: Nil => List(onlyResult)
      case Nil => Nil
      case moreResults =>
        val moreExact = moreResults.filter { case (_, sym) =>
          correctInTreeContext(sym)
        }
        (if (moreExact.nonEmpty) moreExact else moreResults).map(_._1)
    }
  }

}
