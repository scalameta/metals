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

    /**
     * Declaration is set only for parameters, defs/vals/vars without rhs,
     * type parameterss and inside pattern matches. In all those cases we don't
     * have a specific value.
     */
    private def isDeclaration(tree: compiler.Tree) = tree match {
      case _: compiler.Bind => true
      case df: compiler.ValOrDefDef => df.rhs.isEmpty
      case tdef: compiler.TypeDef =>
        tdef.rhs.symbol == compiler.NoSymbol
      case _ => false
    }

    /**
     * Definition is set only for defs/vals/vars/type with rhs.
     * We don;t want to set it for enum cases despite the fact
     * that the compiler sees them as vals, as it's not clear
     * if they should be declaration/definition at all.
     */
    private def isDefinition(tree: compiler.Tree) = tree match {
      case df: compiler.ValOrDefDef => df.rhs.nonEmpty
      case tdef: compiler.TypeDef => tdef.rhs.symbol != compiler.NoSymbol
      case _ => false
    }

    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: compiler.Position,
        symbol: Option[compiler.Symbol]
    ): Option[Node] = {
      val sym = symbol.fold(tree.symbol)(identity)
      if (
        !pos.isDefined || sym == null || sym == compiler.NoSymbol || sym.isConstructor
      ) None
      else
        Some(
          makeNode(
            sym = sym,
            pos = adjust(pos)._1,
            isDefinition = isDefinition(tree),
            isDeclaration = isDeclaration(tree)
          )
        )
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
      pos: Collector.compiler.Position,
      isDefinition: Boolean,
      isDeclaration: Boolean
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
    if (isDeclaration) addPwrToMod(SemanticTokenModifiers.Declaration)
    if (isDefinition) addPwrToMod(SemanticTokenModifiers.Definition)

    TokenNode(pos.start, pos.end, typ, mod)

  }

}
