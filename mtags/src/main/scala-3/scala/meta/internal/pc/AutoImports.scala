package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportEdits
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j as l}

object AutoImports:

  enum AutoImport:

    /**
     * Trivial import: `Future -> (Future, import scala.concurrent.Future)`
     */
    case Simple(sym: Symbol)

    /**
     * Rename symbol owner and add renamed prefix to tpe symbol
     * `Map -> (ju.Map, import java.{util => ju})`
     */
    case Renamed(sym: Symbol, ownerRename: String)

    /**
     *  Import owner and add prefix to tpe symbol
     * `Map -> (mutable.Map, import scala.collection.mutable)`
     */
    case SpecifiedOwner(sym: Symbol)
  end AutoImport

  object AutoImport:
    def renamedOrSpecified(sym: Symbol, ownerRename: String)(using
        Context
    ): AutoImport =
      if sym.owner.showName == ownerRename then SpecifiedOwner(sym)
      else Renamed(sym, ownerRename)

  def generator(
      pos: SourcePosition,
      text: String,
      tree: Tree,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig
  ): AutoImportsGenerator =

    import indexedContext.ctx

    val importPos = autoImportPosition(pos, text, tree)
    val renameConfig: Map[Symbol, String] =
      config.symbolPrefixes.asScala.flatMap { (from, to) =>
        val fullName = from.stripSuffix("/").replace("/", ".")
        val pkg = requiredPackage(fullName)
        val rename = to.stripSuffix(".").stripSuffix("#")
        List(pkg, pkg.moduleClass)
          .filter(_ != NoSymbol)
          .map((_, rename))
      }.toMap

    val renames =
      (sym: Symbol) =>
        indexedContext
          .rename(sym)
          .orElse(renameConfig.get(sym))

    new AutoImportsGenerator(
      pos,
      importPos,
      indexedContext.importContext,
      renames
    )
  end generator

  case class AutoImportEdits(
      nameEdit: Option[l.TextEdit],
      importEdit: Option[l.TextEdit]
  ):

    def edits: List[l.TextEdit] = List(nameEdit, importEdit).flatten

  object AutoImportEdits:

    def apply(name: l.TextEdit, imp: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(name), Some(imp))
    def importOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(None, Some(edit))
    def nameOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(edit), None)

  class AutoImportsGenerator(
      pos: SourcePosition,
      importPosition: AutoImportPosition,
      indexedContext: IndexedContext,
      renames: Symbol => Option[String]
  ):

    import indexedContext.ctx

    def forSymbol(symbol: Symbol): Option[List[l.TextEdit]] =
      editsForSymbol(symbol).map(_.edits)

    def editsForSymbol(symbol: Symbol): Option[AutoImportEdits] =
      inferAutoImport(symbol).map { ai =>
        def mkImportEdit = importEdit(List(ai), importPosition)
        ai match
          case _: AutoImport.Simple =>
            AutoImportEdits.importOnly(mkImportEdit)
          case AutoImport.SpecifiedOwner(sym)
              if indexedContext.lookupSym(sym.owner).exists =>
            AutoImportEdits.nameOnly(specifyOwnerEdit(sym, sym.owner.showName))
          case AutoImport.SpecifiedOwner(sym) =>
            AutoImportEdits(
              specifyOwnerEdit(sym, sym.owner.showName),
              mkImportEdit
            )
          case AutoImport.Renamed(sym, ownerRename)
              if indexedContext.hasRename(sym.owner, ownerRename) =>
            AutoImportEdits.nameOnly(specifyOwnerEdit(sym, ownerRename))
          case AutoImport.Renamed(sym, ownerRename) =>
            AutoImportEdits(specifyOwnerEdit(sym, ownerRename), mkImportEdit)
        end match
      }

    private def inferAutoImport(symbol: Symbol): Option[AutoImport] =
      indexedContext.lookupSym(symbol) match
        case IndexedContext.Result.Missing => Some(AutoImport.Simple(symbol))
        case IndexedContext.Result.Conflict =>
          val owner = symbol.owner
          renames(owner) match
            case Some(rename) =>
              Some(AutoImport.renamedOrSpecified(symbol, rename))
            case _ => None
        case IndexedContext.Result.InScope => None

    private def specifyOwnerEdit(symbol: Symbol, owner: String): l.TextEdit =
      val line = pos.startLine
      new l.TextEdit(pos.toLSP, s"$owner.${symbol.nameBackticked}")

    private def importEdit(
        values: List[AutoImport],
        importPosition: AutoImportPosition
    )(using Context): l.TextEdit =
      val indent = " " * importPosition.indent
      val topPadding =
        if importPosition.padTop then "\n"
        else ""

      val formatted = values
        .map({
          case AutoImport.Simple(sym) => importName(sym)
          case AutoImport.SpecifiedOwner(sym) => importName(sym.owner)
          case AutoImport.Renamed(sym, rename) =>
            s"${importName(sym.owner.owner)}.{${sym.owner.nameBackticked} => $rename}"
        })
        .map(selector => s"${indent}import $selector")
        .mkString(topPadding, "\n", "\n")

      val editPos = pos.withSpan(Spans.Span(importPosition.offset)).toLSP
      new l.TextEdit(editPos, formatted)
    end importEdit

    private def importName(sym: Symbol): String =
      if indexedContext.toplevelClashes(sym) then
        s"_root_.${sym.fullNameBackticked}"
      else sym.fullNameBackticked
  end AutoImportsGenerator

  private def autoImportPosition(
      pos: SourcePosition,
      text: String,
      tree: Tree
  )(using Context): AutoImportPosition =

    @tailrec
    def lastPackageDef(
        prev: Option[PackageDef],
        tree: Tree
    ): Option[PackageDef] =
      tree match
        case curr @ PackageDef(_, (next: PackageDef) :: Nil)
            if !curr.symbol.isPackageObject =>
          lastPackageDef(Some(curr), next)
        case pkg: PackageDef if !pkg.symbol.isPackageObject => Some(pkg)
        case _ => prev

    def ammoniteObjectBody(tree: Tree)(using Context): Option[Template] =
      tree match
        case PackageDef(_, stats) =>
          stats.flatMap {
            case s: PackageDef => ammoniteObjectBody(s)
            case TypeDef(_, t @ Template(defDef, _, _, _))
                if defDef.symbol.showName == "<init>" =>
              Some(t)
            case _ => None
          }.headOption
        case _ => None

    // Naive way to find the start discounting any first lines that may be
    // scala-cli directives.
    @tailrec
    def findStart(text: String, index: Int): Int =
      if text.startsWith("//") then
        val newline = text.indexOf("\n")
        if newline != -1 then
          findStart(text.drop(newline + 1), index + newline + 1)
        else index + newline + 1
      else index

    def forScalaSource: Option[AutoImportPosition] =
      lastPackageDef(None, tree).map { pkg =>
        val lastImportStatement =
          pkg.stats.takeWhile(_.isInstanceOf[Import]).lastOption
        val (lineNumber, padTop) = lastImportStatement match
          case Some(stm) => (stm.endPos.line + 1, false)
          case None if pkg.pid.symbol.isEmptyPackage =>
            (pos.source.offsetToLine(findStart(text, 0)), false)
          case None =>
            val pos = pkg.pid.endPos
            val line =
              // pos point at the last NL
              if pos.endColumn == 0 then math.max(0, pos.line - 1)
              else pos.line + 1
            (line, true)
        val offset = pos.source.lineToOffset(lineNumber)
        new AutoImportPosition(offset, text, padTop)
      }

    def forScript: Option[AutoImportPosition] =
      ammoniteObjectBody(tree).map { tmpl =>
        val lastImportStatement =
          tmpl.body.takeWhile(_.isInstanceOf[Import]).lastOption
        val (lineNumber, padTop) = lastImportStatement match
          case Some(stm) => (stm.endPos.line + 1, false)
          case None => (tmpl.self.srcPos.line, false)
        val offset = pos.source.lineToOffset(lineNumber)
        new AutoImportPosition(offset, text, padTop)
      }

    val path = pos.source.path

    def fileStart =
      AutoImportPosition(findStart(text, 0), 0, padTop = false)

    val ammonite =
      if path.endsWith(".sc.scala") then forScript else None
    ammonite
      .orElse(forScalaSource)
      .getOrElse(fileStart)
  end autoImportPosition

end AutoImports
