package scala.meta.internal.pc
package completions

import java.{util as ju}

import scala.collection.JavaConverters.*
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.IndexedContext.Result
import scala.meta.internal.pc.MetalsInteractive.*
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Definitions
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.NoType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import org.eclipse.{lsp4j as l}
import scala.meta.internal.pc.printer.ShortenedNames

object CaseKeywordCompletion:
  def contribute(
      selector: Tree,
      completionPos: CompletionPos,
      typedTree: Tree,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
      parent: Tree,
  ): List[CompletionValue] =
    import indexedContext.ctx
    val pos = completionPos.sourcePos
    val definitions = indexedContext.ctx.definitions
    val text = pos.source.content().mkString
    lazy val autoImportsGen = AutoImports.generator(
      pos,
      text,
      typedTree,
      indexedContext,
      config,
    )

    val parents: Parents = selector match
      case EmptyTree =>
        val seenFromType = parent match
          case TreeApply(fun, _) if fun.tpe != null && !fun.tpe.isErroneous =>
            fun.tpe
          case _ =>
            parent.seenFrom(parent.symbol)._1
        seenFromType.paramInfoss match
          case (head :: Nil) :: _
              if definitions.isFunctionType(head) || head.isRef(
                definitions.PartialFunctionClass
              ) =>
            val dealiased = head.widenDealias
            val argTypes =
              head.argTypes.init
            new Parents(argTypes, definitions)
          case _ =>
            new Parents(NoType, definitions)
      case sel => new Parents(sel.tpe, definitions)

    val result = ListBuffer.empty[CompletionValue]
    val isVisited = mutable.Set.empty[Symbol]

    def visit(sym: Symbol, name: String, autoImports: List[l.TextEdit]): Unit =
      val isValid = !isVisited(sym) && !parents.isParent(sym)
        && (sym.is(Case) || sym.is(Flags.Module) || sym.isClass)
        && parents.isSubClass(sym, false)
      def recordVisit(s: Symbol): Unit =
        if s != NoSymbol && !isVisited(s) then
          isVisited += s
          recordVisit(s.moduleClass)
          recordVisit(s.sourceModule)
      if isValid then
        recordVisit(sym)
        result += toCompletionValue(
          sym,
          name,
          indexedContext,
          completionPos.toEditRange,
          autoImports,
          config.isCompletionSnippetsEnabled(),
        )
    end visit
    val selectorSym = parents.selector.typeSymbol
    val shortenedNames = ShortenedNames(indexedContext)
    indexedContext.scopeSymbols.iterator
      .foreach(s => visit(s.info.dealias.typeSymbol, s.decodedName, Nil))

    selectorSym.sealedStrictDescendants.foreach { sym =>
      if !(sym.is(Sealed) && (sym.is(Abstract) || sym.is(Trait))) then
        val autoImport = autoImportsGen.forSymbol(sym)
        autoImport match
          case Some(value) =>
            visit(sym.info.dealias.typeSymbol, sym.decodedName, value)
          case scala.None =>
            visit(sym.info.dealias.typeSymbol, sym.showFullName, Nil)
      else ()
    }

    if definitions.isTupleClass(selectorSym)
    then
      result += CompletionValue.CaseKeyword(
        selectorSym,
        s"case ${parents.selector.show} =>",
        Some(
          if config.isCompletionSnippetsEnabled() then "case ($0) =>"
          else "case () =>"
        ),
        Nil,
        Some(completionPos.toEditRange),
        None,
        command = config.parameterHintsCommand().asScala,
      )
    end if
    val res = result.result()
    res
  end contribute
end CaseKeywordCompletion

private def toCompletionValue(
    sym: Symbol,
    name: String,
    indexedContext: IndexedContext,
    editRange: l.Range,
    autoImports: List[l.TextEdit],
    clientSupportsSnippets: Boolean,
    isSnippet: Boolean = true,
)(using Context): CompletionValue =
  sym.info
  val isModuleLike =
    sym.is(Flags.Module) || sym.isOneOf(JavaEnumTrait) || sym.isOneOf(
      JavaEnumValue
    )
  if sym.is(Case) || isModuleLike then
    val isInfixEligible = indexedContext.lookupSym(sym) == Result.InScope
      || autoImports.nonEmpty

    val infixPattern: Option[String] =
      if isInfixEligible && sym.is(Case) && !Character.isUnicodeIdentifierStart(
          sym.decodedName.head
        )
      then
        sym.primaryConstructor.paramSymss match
          case (a :: b :: Nil) :: Nil =>
            Some(
              s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
            )
          case _ :: (a :: b :: Nil) :: _ =>
            Some(
              s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
            )
          case _ => None
      else None
    val pattern = infixPattern.getOrElse {
      val suffix =
        if isModuleLike then ""
        else
          sym.primaryConstructor.paramSymss match
            case Nil => "()"
            case tparams :: params :: _ =>
              params
                .map(param => param.showName)
                .mkString("(", ", ", ")")
            case head :: _ =>
              head
                .map(param => param.showName)
                .mkString("(", ", ", ")")
      name + suffix
    }
    val label = s"case $pattern =>"
    CompletionValue.CaseKeyword(
      sym,
      label,
      Some(label + (if isSnippet && clientSupportsSnippets then " $0" else "")),
      autoImports,
      Some(editRange),
      None,
      None,
    )
  else
    val suffix = sym.typeParams match
      case Nil => ""
      case tparams => tparams.map(_ => "_").mkString("[", ", ", "]")
    CompletionValue.CaseKeyword(
      sym,
      s"case _: $name$suffix =>",
      Some(
        if isSnippet && clientSupportsSnippets then
          s"case $${0:_}: $name$suffix => "
        else s"case _: $name$suffix =>"
      ),
      autoImports,
      Some(editRange),
      None,
      None,
    )
  end if
end toCompletionValue


class Parents(val selector: Type, definitions: Definitions)(using Context):
  def this(tpes: List[Type], definitions: Definitions)(using Context) =
    this(
      tpes match
        case Nil => NoType
        case head :: Nil => head
        case _ => definitions.tupleType(tpes)
      ,
      definitions,
    )

  val isParent: Set[Symbol] =
    Set(selector.typeSymbol, selector.typeSymbol.companion)
      .filterNot(_ == NoSymbol)
  val isBottom: Set[Symbol] = Set[Symbol](
    definitions.NullClass,
    definitions.NothingClass,
  )
  def isSubClass(typeSymbol: Symbol, includeReverse: Boolean)(using
      Context
  ): Boolean =
    !isBottom(typeSymbol) &&
      isParent.exists { parent =>
        typeSymbol.isSubClass(parent) ||
        (includeReverse && parent.isSubClass(typeSymbol))
      }
end Parents
