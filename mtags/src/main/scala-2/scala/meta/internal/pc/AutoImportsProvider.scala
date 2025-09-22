package scala.meta.internal.pc

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

    val visitor =
      new CompilerSearchVisitor(context, visit)
    search.search(name, buildTargetIdentifier, visitor)

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

    val all = symbols.result().collect {
      case sym
          if isExactMatch(sym, name) && context.isAccessible(
            sym,
            sym.info
          ) && !sym.owner.isEmptyPackageClass =>
        val pkg = sym.owner.fullName
        val edits = importPosition match {
          // if we are in import section just specify full name
          case None if isInImportTree =>
            val nameEdit = new l.TextEdit(namePos, sym.fullNameSyntax)
            List(nameEdit)
          case None =>
            // No import position means we can't insert an import without clashing with
            // existing symbols in scope, so we just do nothing
            Nil
          case Some(value) =>
            val (short, edits) = ShortenedNames.synthesize(
              TypeRef(ThisType(sym.owner), sym, Nil),
              pos,
              context,
              value
            )
            val nameEdit = new l.TextEdit(namePos, short)
            nameEdit :: edits
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
        (AutoImportsResultImpl(pkg, edits.asJava), sym)
    }

    all match {
      case (onlyResult, _) :: Nil => List(onlyResult)
      case Nil => Nil
      case moreResults =>
        val moreExact = moreResults.filter { case (_, sym) =>
          correctInTreeContext(sym)
        }
        if (moreExact.nonEmpty) moreExact.map(_._1)
        else moreResults.map(_._1)
    }
  }

}
