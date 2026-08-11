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

  private case class RenderedResult(
      result: AutoImportsResult,
      symbol: Symbol,
      isPackageObjectImport: Boolean
  )

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
    val symbols = List.newBuilder[Symbol]

    def visit(sym: Symbol): Boolean = {
      val id = sym.fullName
      if (!isSeen(id)) {
        isSeen += id
        symbols += sym
        true
      }
      false
    }

    compiler.searchOutline(visit, name)

    val seenPackageObjectMembers = mutable.Set.empty[(String, Boolean)]
    def visitPackageObjectMember(sym: Symbol): Boolean = {
      val id = (sym.fullName, sym.isType)
      if (seenPackageObjectMembers.add(id)) {
        symbols += sym
        true
      } else false
    }

    // symbols importable only through a package object (issue #2583), mapped
    // to the package classes the import can go through; searched before the
    // classfile-based sources so that the import a library curates through
    // its package object is offered before the definition's own package
    val visitedThroughPackageObject = compiler.searchPackageObjectMembers(
      name,
      context,
      visitPackageObjectMember,
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
    ): RenderedResult = {
      val importOwner = throughPackageObject.getOrElse(sym.owner)
      val pkg = importOwner.fullName
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
      RenderedResult(
        AutoImportsResultImpl(
          pkg,
          edits.asJava,
          Optional.of(semanticdbSymbol(sym))
        ),
        sym,
        throughPackageObject.isDefined
      )
    }

    // Package-object members are resolved by name in both namespaces without
    // any context, so a term-only member (a `val`) can come back for an
    // unresolved type and vice versa; importing it would not fix the error,
    // so keep only the candidates whose namespace fits the tree position.
    def namespaceMatchesPosition(sym: Symbol): Boolean = {
      def matchesTerm = sym.isTerm || sym.companionModule != NoSymbol
      def matchesType = sym.isType || sym.companionClass != NoSymbol
      lastVisitedParentTrees match {
        // an import brings both namespaces into scope
        case (_: Import) :: _ => true
        case (_: Ident) :: (_: Import) :: _ => true
        case (_: Ident) :: SingletonTypeTree(_) :: _ => matchesTerm
        case (_: Ident) :: (_: TypTree) :: _ => matchesType
        // a bare Ident as the declared type of a val or def
        case (id: Ident) :: (df: ValOrDefDef) :: _ if df.tpt == id =>
          matchesType
        // a bare Ident as the right-hand side of a type alias
        case (id: Ident) :: (td: TypeDef) :: _ if td.rhs == id =>
          matchesType
        // constructor calls and template parents need a class type
        case (_: Ident) :: ((_: New) | (_: Template)) :: _ => matchesType
        // any other position is an expression and needs a term
        case _ => matchesTerm
      }
    }

    val all = symbols.result().flatMap { sym =>
      if (
        isExactMatch(sym, name) && context.isAccessible(
          sym,
          sym.info
        ) && !sym.owner.isEmptyPackageClass
      ) {
        // A symbol exposed by a package object is importable through the
        // enclosing package rather than its declared owner (issue #2583); a
        // symbol exposed by several package objects is importable through
        // each of them, so offer one import per package.
        val importOwners = visitedThroughPackageObject.get(sym) match {
          case Some(pkgClasses) if namespaceMatchesPosition(sym) =>
            pkgClasses.map(Some(_))
          case Some(_) => Nil
          case None => List(None)
        }
        importOwners.map(renderResult(sym, _))
      } else Nil
    }

    // a package object can expose the same name in both namespaces (e.g.
    // doobie inherits `type Transactor` and `val Transactor`); both render
    // the same import statement, so offer only one code action for them
    def dedupPackageObjectResults(
        results: List[RenderedResult]
    ): List[AutoImportsResult] = {
      val seen = mutable.Set.empty[(String, ju.List[l.TextEdit])]
      results.collect {
        case rendered if !rendered.isPackageObjectImport => rendered.result
        case rendered
            if seen.add(
              (rendered.result.packageName(), rendered.result.edits())
            ) =>
          rendered.result
      }
    }

    all match {
      case onlyResult :: Nil => List(onlyResult.result)
      case Nil => Nil
      case moreResults =>
        val moreExact = moreResults.filter { rendered =>
          correctInTreeContext(rendered.symbol)
        }
        val results = if (moreExact.nonEmpty) moreExact else moreResults
        dedupPackageObjectResults(results)
    }
  }

}
