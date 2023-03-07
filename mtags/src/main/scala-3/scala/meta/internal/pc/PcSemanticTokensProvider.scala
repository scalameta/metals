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
  object Collector extends PcCollector[Option[Node]](driver, params):
    override def collect(
        parent: Option[Tree]
    )(tree: Tree, pos: SourcePosition, symbol: Option[Symbol]): Option[Node] =
      val sym = symbol.fold(tree.symbol)(s => s)
      if !pos.exists || sym == null || sym == NoSymbol then None
      else Some(makeNode(sym, adjust(pos)._1))

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
  ): Node =

    var mod: Int = 0
    def addPwrToMod(tokenID: String) =
      val place: Int = getModifierId(tokenID)
      if place != -1 then mod += (1 << place)
    // get Type
    val typ =
      if sym.is(Flags.Param) && !sym.isTypeParam
      then getTypeId(SemanticTokenTypes.Parameter)
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

    TokenNode(pos.start, pos.`end`, typ, mod)
  end makeNode

end PcSemanticTokensProvider
