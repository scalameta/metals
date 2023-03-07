package scala.meta.internal.pc
import scala.meta.internal.pc.SemanticTokens._
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file(@param params)
 *  according to the LSP specification.
 */
final class PcSemanticTokensProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: VirtualFileParams
) {
  // Initialize Tree
  object Collector extends PcCollector[Option[Node]](cp, params) {
    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: compiler.Position,
        symbol: Option[compiler.Symbol]
    ): Option[Node] = {
      val sym = symbol.fold(tree.symbol)(s => s)
      if (
        !pos.isDefined || sym == null || sym == compiler.NoSymbol || sym.isConstructor
      ) None
      else Some(makeNode(sym, adjust(pos)._1))
    }
  }
  def provide(): List[Node] =
    Collector
      .result()
      .flatten
      .sortWith((n1, n2) =>
        if (n1.start() == n2.start()) n1.end() < n2.end()
        else n1.start() < n2.start()
      )

  def makeNode(
      sym: Collector.compiler.Symbol,
      pos: Collector.compiler.Position
  ): Node = {
    var mod: Int = 0
    def addPwrToMod(tokenID: String) = {
      val place: Int = getModifierId(tokenID)
      if (place != -1) mod += (1 << place)
    }
    // get Type
    val typ =
      if (sym.isValueParameter)
        getTypeId(SemanticTokenTypes.Parameter)
      else if (sym.isTypeParameter || sym.isTypeSkolem)
        getTypeId(SemanticTokenTypes.TypeParameter)
      else if (
        sym.companion
          .hasFlag(scala.reflect.internal.ModifierFlags.JAVA_ENUM)
      )
        getTypeId(SemanticTokenTypes.Enum)
      else if (sym.hasFlag(scala.reflect.internal.ModifierFlags.JAVA_ENUM))
        getTypeId(SemanticTokenTypes.EnumMember)
      // See symbol.keystring about following conditions.
      else if (sym.isJavaInterface)
        getTypeId(SemanticTokenTypes.Interface) // "interface"
      else if (sym.isTrait)
        getTypeId(SemanticTokenTypes.Interface) // "trait"
      else if (sym.isClass) getTypeId(SemanticTokenTypes.Class) // "class"
      else if (sym.isType && !sym.isParameter)
        getTypeId(SemanticTokenTypes.Type) // "type"
      else if (sym.isVariable)
        getTypeId(SemanticTokenTypes.Variable) // "var"
      else if (sym.hasPackageFlag)
        getTypeId(SemanticTokenTypes.Namespace) // "package"
      else if (sym.isModule) getTypeId(SemanticTokenTypes.Class) // "object"
      else if (sym.isSourceMethod)
        if (sym.isGetter | sym.isSetter)
          getTypeId(SemanticTokenTypes.Variable)
        else getTypeId(SemanticTokenTypes.Method) // "def"
      else if (sym.isTerm && (!sym.isParameter || sym.isParamAccessor)) {
        addPwrToMod(SemanticTokenModifiers.Readonly)
        getTypeId(SemanticTokenTypes.Variable) // "val"
      } else -1
    // Modifiers except by ReadOnly
    if (sym.isAbstract) addPwrToMod(SemanticTokenModifiers.Abstract)
    if (sym.isDeprecated) addPwrToMod(SemanticTokenModifiers.Deprecated)
    if (sym.owner.isModule) addPwrToMod(SemanticTokenModifiers.Static)

    TokenNode(pos.start, pos.end, typ, mod)

  }

}
