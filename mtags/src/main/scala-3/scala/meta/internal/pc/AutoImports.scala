package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportEdits
import scala.meta.internal.pc.printer.ShortenedNames.ShortName
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
     * Rename symbol owner and add renamed prefix to tpe symbol.
     * For example, `Renamed(sym = <java.util.Map>, ownerRename = "ju")` represents
     * complete `ju.Map` while auto import `import java.{util => ju}`.
     * `toEdits` method convert `Renamed` to
     * AutoImportEdits(
     *  nameEdit = "ju.Map",
     *  importEdit = "import java.{util => ju})"
     * )
     */
    case Renamed(sym: Symbol, ownerRename: String)

    /**
     * Rename symbol itself, import only.
     * For example, `SelfRenamed(sym = <java.util>, name = "ju")` represents
     * auto import `import java.{util => ju}` without completing anything, unlike `Renamed`.
     *
     * `toEdits` method convert `SelfRenamed` to
     * AutoImportEdits(
     *  nameEdit = None,
     *  importEdit = "import java.{util => ju})"
     * )
     */
    case SelfRenamed(sym: Symbol, name: String)

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

    def renameConfigMap(config: PresentationCompilerConfig)(using
        Context
    ): Map[Symbol, String] =
      config.symbolPrefixes.asScala.flatMap { (from, to) =>
        val pkg = SemanticdbSymbols.inverseSemanticdbSymbol(from)
        val rename = to.stripSuffix(".").stripSuffix("#")
        List(pkg, pkg.map(_.moduleClass)).flatten
          .filter(_ != NoSymbol)
          .map((_, rename))
      }.toMap
  end AutoImport

  /**
   * Returns AutoImportsGenerator
   *
   * @param pos A source position where the autoImport is invoked
   * @param text Source text of the file
   * @param tree A typed tree of the file
   * @param indexedContext A context of the position where the autoImport is invoked
   * @param config A presentation compiler config, this is used for renames
   */
  def generator(
      pos: SourcePosition,
      text: String,
      tree: Tree,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
  ): AutoImportsGenerator =

    import indexedContext.ctx

    val importPos = autoImportPosition(pos, text, tree)
    val renameConfig: Map[Symbol, String] = AutoImport.renameConfigMap(config)

    val renames =
      (sym: Symbol) =>
        indexedContext
          .rename(sym)
          .orElse(renameConfig.get(sym))

    new AutoImportsGenerator(
      pos,
      importPos,
      indexedContext,
      renames,
    )
  end generator

  case class AutoImportEdits(
      nameEdit: Option[l.TextEdit],
      importEdit: Option[l.TextEdit],
  ):

    def edits: List[l.TextEdit] = List(nameEdit, importEdit).flatten

  object AutoImportEdits:

    def apply(name: l.TextEdit, imp: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(name), Some(imp))
    def importOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(None, Some(edit))
    def nameOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(edit), None)

  /**
   * AutoImportsGenerator generates TextEdits of auto-imports
   * for the given symbols.
   *
   * @param pos A source position where the autoImport is invoked
   * @param importPosition A position to insert new imports
   * @param indexedContext A context of the position where the autoImport is invoked
   * @param renames A function that returns the name of the given symbol which is renamed on import statement.
   */
  class AutoImportsGenerator(
      pos: SourcePosition,
      importPosition: AutoImportPosition,
      indexedContext: IndexedContext,
      renames: Symbol => Option[String],
  ):

    import indexedContext.ctx

    def forSymbol(symbol: Symbol): Option[List[l.TextEdit]] =
      editsForSymbol(symbol).map(_.edits)

    /**
     * Construct auto imports for the given ShortName,
     * if the shortName has different name with it's symbol name,
     * generate renamed import. For example,
     * `ShortName("ju", <java.util>)` => `import java.{util => ju}`.
     */
    def forShortName(shortName: ShortName): Option[List[l.TextEdit]] =
      if shortName.isRename then Some(toEdits(shortName.asImport).edits)
      else forSymbol(shortName.symbol)

    /**
     * @param symbol A missing symbol to auto-import
     */
    def editsForSymbol(symbol: Symbol): Option[AutoImportEdits] =
      inferAutoImport(symbol).map { ai => toEdits(ai) }

    private def toEdits(ai: AutoImport): AutoImportEdits =
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
            mkImportEdit,
          )
        case AutoImport.Renamed(sym, ownerRename)
            if indexedContext.hasRename(sym.owner, ownerRename) =>
          AutoImportEdits.nameOnly(specifyOwnerEdit(sym, ownerRename))
        case AutoImport.Renamed(sym, ownerRename) =>
          AutoImportEdits(specifyOwnerEdit(sym, ownerRename), mkImportEdit)
        case AutoImport.SelfRenamed(_, _) =>
          AutoImportEdits.importOnly(mkImportEdit)
      end match
    end toEdits

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
        importPosition: AutoImportPosition,
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
          case AutoImport.SelfRenamed(sym, rename) =>
            s"${importName(sym.owner)}.{${sym.nameBackticked} => $rename}"
        })
        .map(selector => s"${indent}import $selector")
        .mkString(topPadding, "\n", "\n")

      val editPos = pos.withSpan(Spans.Span(importPosition.offset)).toLSP
      new l.TextEdit(editPos, formatted)
    end importEdit

    private def importName(sym: Symbol): String =
      if indexedContext.importContext.toplevelClashes(sym) then
        s"_root_.${sym.fullNameBackticked}"
      else sym.fullNameBackticked
  end AutoImportsGenerator

  private def autoImportPosition(
      pos: SourcePosition,
      text: String,
      tree: Tree,
  )(using Context): AutoImportPosition =

    @tailrec
    def lastPackageDef(
        prev: Option[PackageDef],
        tree: Tree,
    ): Option[PackageDef] =
      tree match
        case curr @ PackageDef(_, (next: PackageDef) :: Nil)
            if !curr.symbol.isPackageObject =>
          lastPackageDef(Some(curr), next)
        case pkg: PackageDef if !pkg.symbol.isPackageObject => Some(pkg)
        case _ => prev

    def firstObjectBody(tree: Tree)(using Context): Option[Template] =
      tree match
        case PackageDef(_, stats) =>
          stats.flatMap {
            case s: PackageDef => firstObjectBody(s)
            case TypeDef(_, t @ Template(defDef, _, _, _))
                if defDef.symbol.showName == "<init>" =>
              Some(t)
            case _ => None
          }.headOption
        case _ => None

    def forScalaSource: Option[AutoImportPosition] =
      lastPackageDef(None, tree).map { pkg =>
        val lastImportStatement =
          pkg.stats.takeWhile(_.isInstanceOf[Import]).lastOption
        val (lineNumber, padTop) = lastImportStatement match
          case Some(stm) => (stm.endPos.line + 1, false)
          case None if pkg.pid.symbol.isEmptyPackage =>
            val offset =
              ScriptFirstImportPosition.skipUsingDirectivesOffset(text)
            (pos.source.offsetToLine(offset), false)
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

    def forScript(isAmmonite: Boolean): Option[AutoImportPosition] =
      firstObjectBody(tree).map { tmpl =>
        val lastImportStatement =
          tmpl.body.takeWhile(_.isInstanceOf[Import]).lastOption
        val offset = lastImportStatement match
          case Some(stm) =>
            val offset = pos.source.lineToOffset(stm.endPos.line + 1)
            offset
          case None =>
            val scriptOffset =
              if isAmmonite then
                ScriptFirstImportPosition.ammoniteScStartOffset(text)
              else ScriptFirstImportPosition.scalaCliScStartOffset(text)

            scriptOffset.getOrElse(
              pos.source.lineToOffset(tmpl.self.srcPos.line)
            )
        new AutoImportPosition(offset, text, false)
      }
    end forScript

    val path = pos.source.path

    def fileStart =
      AutoImportPosition(
        ScriptFirstImportPosition.skipUsingDirectivesOffset(text),
        0,
        padTop = false,
      )

    val scriptPos =
      if path.endsWith(".sc") then forScript(isAmmonite = false)
      else if path.endsWith(".amm.sc.scala") then forScript(isAmmonite = true)
      else None

    scriptPos
      .orElse(forScalaSource)
      .getOrElse(fileStart)
  end autoImportPosition

end AutoImports
