package scala.meta.internal.pc
import scala.meta.pc.VirtualFileParams
import scala.meta.tokens._

import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file(@param params)
 *  according to the LSP specification.
 */
final class SemanticTokenProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: VirtualFileParams
) extends SemTokenProvider {
  type Sym = Collector.compiler.Symbol
  type Pos = scala.reflect.api.Position
  import cp._

  implicit val ord: Ordering[NodeInfo] = Ordering.fromLessThan((ni1, ni2) =>
    if (ni1.pos.start == ni2.pos.start) ni1.pos.end < ni2.pos.end
    else ni1.pos.start < ni2.pos.start
  )

  override def getTokens(): Tokens = {
    import scala.meta._
    params.text().tokenize.get
  }

  // Initialize Tree
  object Collector extends PcCollector[NodeInfo](cp, params) {
    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: Position,
        sym: Option[compiler.Symbol]
    ): NodeInfo = {
      val pos0 = adjust(pos)._1
      val symbol = sym.getOrElse(tree.symbol)
      if (symbol == null)
        NodeInfo(compiler.NoSymbol, pos0)
      else
        NodeInfo(symbol, pos0)
    }
  }

  override val nodes: List[NodeInfo] =
    Collector.result.sorted

  /**
   * The position of @param tk must be incremented from the previous call.
   */
  def pickFromTraversed(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[NodeInfo]
  ): Option[(NodeInfo, List[NodeInfo])] = {

    def isTarget(node: NodeInfo): Boolean =
      node.pos.start == tk.pos.start &&
        node.pos.end == tk.pos.end &&
        node.sym != NoSymbol

    def classOverMethod(nodes: List[NodeInfo]): Option[NodeInfo] =
      nodes.find { case ni => !ni.sym.isMethod } match {
        case Some(node) => Some(node)
        case None => nodes.headOption
      }

    val candidates = nodesIterator.dropWhile(_.pos.start < tk.pos.start)
    candidates
      .takeWhile(_.pos.start == tk.pos.start)
      .filter(isTarget) match {
      case Nil => None
      case node :: Nil => Some((node, candidates))
      case manyNodes =>
        manyNodes.filter(ni =>
          Collector
            .symbolAlternatives(ni.sym)
            .exists(_.decodedName == tk.text)
        ) match {
          case Nil => classOverMethod(manyNodes).map(ni => (ni, candidates))
          case matchingNames
              if matchingNames.exists(!_.sym.owner.isPrimaryConstructor) =>
            matchingNames.collectFirst {
              case ni if !ni.sym.owner.isPrimaryConstructor => (ni, candidates)
            }
          case matchingNames =>
            classOverMethod(matchingNames).map(ni => (ni, candidates))
        }

    }
  }

  override protected def trySoftKeyword(tk: Token): Integer = -1

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  override def identTypeAndMod(
      ident: Token.Ident,
      nodesIterator: List[NodeInfo]
  ): (Int, Int, List[NodeInfo]) = {
    lazy val default =
      (-1, 0, nodesIterator.dropWhile(_.pos.start < ident.pos.start))

    val ret =
      for (
        (nodeInfo, nodesIterator) <- pickFromTraversed(ident, nodesIterator);
        sym = nodeInfo.sym
      ) yield {
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
          else if (isOperatorName(ident)) getTypeId(SemanticTokenTypes.Operator)
          // Java Enum
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

        (typ, mod, nodesIterator)

      }
    ret.getOrElse(default)

  }
}
