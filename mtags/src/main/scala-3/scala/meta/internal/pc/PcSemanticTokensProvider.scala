package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.SemanticTokens.*
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file(@param params)
 *  according to the LSP specification.
 */
final class PcSemanticTokensProvider(
    driver: InteractiveDriver,
    params: VirtualFileParams,
):
  /**
   * Declaration is set for:
   * 1. parameters,
   * 2. defs/vals/vars without rhs,
   * 3. type parameters,
   * In all those cases we don't have a specific value for sure.
   */
  private def isDeclaration(tree: Tree) = tree match
    case df: ValOrDefDef => df.rhs.isEmpty
    case df: TypeDef =>
      df.rhs match
        case _: Template => false
        case _ => df.rhs.isEmpty
    case _ => false

  /**
   * Definition is set for:
   * 1. defs/vals/vars/type with rhs.
   * 2. pattern matches
   *
   * We don't want to set it for enum cases despite the fact
   * that the compiler sees them as vals, as it's not clear
   * if they should be declaration/definition at all.
   */
  private def isDefinition(tree: Tree) = tree match
    case df: Bind => true
    case df: ValOrDefDef =>
      !df.rhs.isEmpty && !df.symbol.isAllOf(Flags.EnumCase)
    case df: TypeDef =>
      df.rhs match
        case _: Template => false
        case _ => !df.rhs.isEmpty
    case _ => false

  object Collector extends PcCollector[Option[Node]](driver, params):
    override def collect(
        parent: Option[Tree]
    )(tree: Tree, pos: SourcePosition, symbol: Option[Symbol]): Option[Node] =
      val sym = symbol.fold(tree.symbol)(identity)
      if !pos.exists || sym == null || sym == NoSymbol then None
      else
        Some(
          makeNode(
            sym = sym,
            pos = adjust(pos)._1,
            isDefinition = isDefinition(tree),
            isDeclaration = isDeclaration(tree),
          )
        )
    end collect
  end Collector

  given Context = Collector.ctx

  def provide(): List[Node] =
    Collector
      .result()
      .flatten
      .sortWith((n1, n2) =>
        if n1.start() == n2.start() then n1.end() < n2.end()
        else n1.start() < n2.start()
      )

  def makeNode(
      sym: Symbol,
      pos: SourcePosition,
      isDefinition: Boolean,
      isDeclaration: Boolean,
  ): Node =

    var mod: Int = 0
    def addPwrToMod(tokenID: String) =
      val place: Int = getModifierId(tokenID)
      if place != -1 then mod += (1 << place)
    // get Type
    val typ =
      if sym.is(Flags.Param) && !sym.isTypeParam
      then
        addPwrToMod(SemanticTokenModifiers.Readonly)
        getTypeId(SemanticTokenTypes.Parameter)
      else if sym.isTypeParam || sym.isSkolem then
        getTypeId(SemanticTokenTypes.TypeParameter)
      else if sym.is(Flags.Enum) || sym.isAllOf(Flags.EnumVal)
      then getTypeId(SemanticTokenTypes.Enum)
      else if sym.is(Flags.Trait) then
        getTypeId(SemanticTokenTypes.Interface) // "interface"
      else if sym.isClass then getTypeId(SemanticTokenTypes.Class) // "class"
      else if sym.isType && !sym.is(Flags.Param) then
        getTypeId(SemanticTokenTypes.Type) // "type"
      else if sym.is(Flags.Mutable) then
        getTypeId(SemanticTokenTypes.Variable) // "var"
      else if sym.is(Flags.Package) then
        getTypeId(SemanticTokenTypes.Namespace) // "package"
      else if sym.is(Flags.Module) then
        getTypeId(SemanticTokenTypes.Class) // "object"
      else if sym.isRealMethod then
        if sym.isGetter | sym.isSetter then
          getTypeId(SemanticTokenTypes.Variable)
        else getTypeId(SemanticTokenTypes.Method) // "def"
      else if isPredefClass(sym) then
        getTypeId(SemanticTokenTypes.Class) // "class"
      else if sym.isTerm &&
        (!sym.is(Flags.Param) || sym.is(Flags.ParamAccessor))
      then
        addPwrToMod(SemanticTokenModifiers.Readonly)
        getTypeId(SemanticTokenTypes.Variable) // "val"
      else -1

    // Modifiers except by ReadOnly
    if sym.is(Flags.Abstract) || sym.isAbstractOrParamType ||
      sym.isOneOf(Flags.AbstractOrTrait)
    then addPwrToMod(SemanticTokenModifiers.Abstract)
    if sym.annotations.exists(_.symbol.decodedName == "deprecated")
    then addPwrToMod(SemanticTokenModifiers.Deprecated)

    if isDeclaration then addPwrToMod(SemanticTokenModifiers.Declaration)
    if isDefinition then addPwrToMod(SemanticTokenModifiers.Definition)

    TokenNode(pos.start, pos.`end`, typ, mod)
  end makeNode

  def isPredefClass(sym: Symbol)(using Context) =
    sym.is(Flags.Method) && sym.info.resultType.typeSymbol.is(Flags.Module)

end PcSemanticTokensProvider
