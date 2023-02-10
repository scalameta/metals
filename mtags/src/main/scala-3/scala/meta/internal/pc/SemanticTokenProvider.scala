package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.parsers.SoftKeywords
import scala.meta.pc.VirtualFileParams
import scala.meta.tokens.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 *  Provides semantic tokens of file(@param params)
 *  according to the LSP specification.
 */
final class SemanticTokenProvider(
    driver: InteractiveDriver,
    params: VirtualFileParams,
) extends SemTokenProvider:
  type Sym = Symbol
  type Pos = SourcePosition

  implicit val ord: Ordering[NodeInfo] = Ordering.fromLessThan((ni1, ni2) =>
    if ni1.pos.start == ni2.pos.start then ni1.pos.end < ni2.pos.end
    else ni1.pos.start < ni2.pos.start
  )
  override def getTokens(): Tokens =
    import scala.meta.*
    implicit val dialect = scala.meta.dialects.Scala3
    params.text().tokenize.get

  object Collector extends PcCollector[NodeInfo](driver, params):
    override def collect(
        parent: Option[Tree]
    )(tree: Tree, pos: SourcePosition, symbol: Option[Symbol]): NodeInfo =
      val sym = symbol.fold(tree.symbol)(s => s)
      if pos.exists then NodeInfo(sym, adjust(pos)._1)
      else NodeInfo(sym, pos)
  given Context = Collector.ctx
  val nodes0: List[NodeInfo] =
    Collector.result()
  override val nodes = nodes0.filter(_.pos.exists).sorted

  /**
   * The position of @param tk must be incremented from the previous call.
   */
  def pickFromTraversed(
      tk: scala.meta.tokens.Token,
      nodesIterator: List[NodeInfo],
  )(using Context): Option[(NodeInfo, List[NodeInfo])] =

    def isTarget(node: NodeInfo): Boolean =
      node.pos.end == tk.pos.end
    val candidates = nodesIterator.dropWhile(_.pos.start < tk.pos.start)

    def filterOutOwner(sym: Symbol): Boolean =
      sym.owner.isPrimaryConstructor || sym.owner.decodedName == "copy"
        || sym.owner.decodedName == "apply"

    def filterOutFlags(sym: Symbol): Boolean =
      sym.is(Flags.Synthetic) ||
        (sym.is(Flags.Implicit) && sym.is(Flags.Method)) ||
        (sym.owner.is(Flags.Implicit) && sym.owner.is(Flags.Method))

    def classOverMethod(nodes: List[NodeInfo]): Option[NodeInfo] =
      nodes.find { case ni => !ni.sym.is(Flags.Method) } match
        case Some(node) => Some(node)
        case None => nodes.headOption

    candidates
      .takeWhile(_.pos.start == tk.pos.start)
      .filter(isTarget) match
      case Nil => None
      case node :: Nil =>
        Some((node, candidates))
      case manyNodes =>
        val preFilter = manyNodes.filter { case NodeInfo(sym, p) =>
          Collector
            .symbolAlternatives(sym)
            .exists(_.decodedName == tk.text)
        }
        preFilter match
          case Nil => classOverMethod(manyNodes).map(ni => (ni, candidates))
          case matchingNames
              if matchingNames.exists(ni =>
                !filterOutOwner(ni.sym) && !filterOutFlags(ni.sym)
              ) =>
            matchingNames.collectFirst {
              case ni if !filterOutOwner(ni.sym) && !filterOutFlags(ni.sym) =>
                (ni, candidates)
            }
          case matchingNames =>
            classOverMethod(matchingNames).map(ni => (ni, candidates))
        end match
    end match
  end pickFromTraversed

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  override def identTypeAndMod(
      ident: Token.Ident,
      nodesIterator: List[NodeInfo],
  ): (Int, Int, List[NodeInfo]) =
    lazy val default =
      (-1, 0, nodesIterator.dropWhile(_.pos.start < ident.pos.start))

    val ret =
      for (
        (nodeInfo, nodesIterator) <- pickFromTraversed(ident, nodesIterator);
        sym = nodeInfo.sym
      ) yield

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
          else if isOperatorName(ident) then
            getTypeId(SemanticTokenTypes.Operator)
          else if sym.is(Flags.Enum) || sym.isAllOf(Flags.EnumVal)
          then getTypeId(SemanticTokenTypes.Enum)
          else if sym.is(Flags.Trait) then
            getTypeId(SemanticTokenTypes.Interface) // "interface"
          else if sym.isClass then
            getTypeId(SemanticTokenTypes.Class) // "class"
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

        (typ, mod, nodesIterator)

    ret.getOrElse(default)

  end identTypeAndMod

  override protected def trySoftKeyword(tk: Token): Integer =
    val SoftKeywordsUnapply = SoftKeywords(scala.meta.dialects.Scala3)
    tk match
      case SoftKeywordsUnapply.KwAs() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwDerives() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwEnd() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwExtension() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwInfix() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwInline() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwOpaque() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwOpen() => getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwTransparent() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case SoftKeywordsUnapply.KwUsing() =>
        getTypeId(SemanticTokenTypes.Keyword)
      case _ => -1
    end match
  end trySoftKeyword

end SemanticTokenProvider
