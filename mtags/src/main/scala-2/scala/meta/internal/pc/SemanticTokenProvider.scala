package scala.meta.internal.pc
import java.util
import java.util.logging.Logger
import java.{util => ju}

import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.Position

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.VirtualFileParams
import scala.meta.tokens._

import org.checkerframework.common.returnsreceiver.qual.This
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/**
 * Corresponds to tests.SemanticHighlightLspSuite
 */
final class SemanticTokenProvider(
    protected val cp: MetalsGlobal // compiler
    ,
    val params: VirtualFileParams,
) {
    val capableTypes= SemanticTokenCapability.tokenTypes
    val capableModifiers = SemanticTokenCapability.tokenModifiers
  // alias for long notation
  def getTypeId(p: String): Int = capableTypes.indexOf(p)


  import cp._
  val unit: RichCompilationUnit = cp.addCompilationUnit(
    params.text(),
    params.uri().toString(),
    None
  )
  cp.typeCheck(unit) // initializing unit
  val (root, source) = (unit.lastBody, unit.source)

  def unitPos(offset: Int): Position = unit.position(offset)
  val nodes: Set[NodeInfo] = traverser.traverse(Set.empty[NodeInfo], root)

  /** main method */
  def provide(): ju.List[Integer] = {

    val buffer = ListBuffer.empty[Integer]
    var currentLine = 0
    var lastLine = 0
    var lastNewlineOffset = 0
    var lastCharStartOffset = 0

    def deltaLine(): Int = currentLine - lastLine
    def deltaStartChar(startPos: Int): Int = {
      if (deltaLine() == 0) startPos - lastCharStartOffset
      else startPos - lastNewlineOffset
    }
    def addTokenToBuffer(
        startPos: Int,
        charSize: Int,
        tokenType: Int,
        tokeModifier: Int
    ): Unit = {
      if (tokenType == -1 && tokeModifier == 0) return

      buffer.++=(
        List(
          deltaLine(), // 1
          deltaStartChar(startPos), // 2
          charSize, // 3
          tokenType, // 4
          tokeModifier // 5
        )
      )
      lastLine = currentLine
      lastCharStartOffset = startPos
    }

    // Loop by token
    import scala.meta._
    for (tk <- params.text().tokenize.toOption.get) yield {

      if (
        tk.getClass.toString.substring(29) != "$Space"
        && tk.getClass.toString.substring(29) != "$LF"
      ) {
        logString += tokenDescriber(tk)
      }

      tk match {
        case _: Token.LF =>
          currentLine += 1
          lastNewlineOffset = tk.pos.end

        case _: Token.Space =>

        // deals multi-line token
        case _: Token.Comment | _: Token.Constant.String =>
          var wkStartPos = tk.pos.start
          var wkCurrentPos = tk.pos.start
          val tokenType = tk match {
            case _: Token.Comment => getTypeId(SemanticTokenTypes.Comment)
            case _ => getTypeId(SemanticTokenTypes.String)
          }
          val tokeModifier = 0

          for (wkStr <- tk.text.toCharArray.toList.map(c => c.toString)) {
            wkCurrentPos += 1

            // Add token to Buffer
            if (wkStr == "\n" | wkCurrentPos == tk.pos.end) {
              val adjustedCurrentPos =
                if (wkStr == "\n") wkCurrentPos - 1
                else wkCurrentPos
              val charSize = adjustedCurrentPos - wkStartPos

              addTokenToBuffer(
                wkStartPos,
                charSize,
                tokenType,
                tokeModifier
              )
              wkStartPos = wkCurrentPos
            }

            // Count such as Token.LF
            if (wkStr == "\n") {
              currentLine += 1
              lastNewlineOffset = wkCurrentPos
            }

          }

        case _ =>
          val (tokenType, tokeModifier, wkLog) = getTypeAndMod(tk)
          logString += wkLog
          addTokenToBuffer(
            tk.pos.start,
            tk.text.size,
            tokenType,
            tokeModifier
          )
      } // end match

    } // end for

    this.logger.info(logString)

    buffer.toList.asJava

  }

  /**
   * This function returns -1 when capable Type is nothing.
   *  TokenTypes that can be on multilines are handled in another func.
   *  See Token.Comment in this file.
   */
  private def typeOfNonIdentToken(
      tk: scala.meta.tokens.Token
  ): Integer = {
    tk match {
      // case _: Token.Ident => // in case of Ident is

      // Alphanumeric keywords
      case _: Token.ModifierKeyword => getTypeId(SemanticTokenTypes.Modifier)
      case _: Token.Keyword => getTypeId(SemanticTokenTypes.Keyword)

      // extends Symbolic keywords
      case _: Token.Hash => getTypeId(SemanticTokenTypes.Keyword)
      // case _: Token.Colon => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.Viewbound => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.LeftArrow => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.Subtype => getTypeId(SemanticTokenTypes.Keyword)
      // case _: Token.Equals => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.RightArrow => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.Supertype => getTypeId(SemanticTokenTypes.Keyword)
      case _: Token.At => getTypeId(SemanticTokenTypes.Keyword)
      case _: Token.Underscore => getTypeId(SemanticTokenTypes.Variable)
      case _: Token.TypeLambdaArrow => getTypeId(SemanticTokenTypes.Operator)
      case _: Token.ContextArrow => getTypeId(SemanticTokenTypes.Operator)

      // Constant
      case _: Token.Constant.Int | _: Token.Constant.Long |
          _: Token.Constant.Float | _: Token.Constant.Double =>
        getTypeId(SemanticTokenTypes.Number)
      case _: Token.Constant.Char => getTypeId(SemanticTokenTypes.String)

      // Default
      case _ => -1
    }

  }

  class NodeInfo(
      var tree: cp.Tree,
      var symbol: cp.Symbol,
      var pos: scala.reflect.internal.util.Position
  ) {
    def this(tree: cp.Tree, pos: cp.Position) = {
      this(tree, tree.symbol, pos)
    }
    def this(imp: cp.Import) = {
      this(imp, null, null)
    }
  }
  object NodeInfo {
    def apply[T <: cp.Symbol](sym: T): NodeInfo = {
      new NodeInfo(null, sym, null)
    }
  }

  /**
   * was written in reference to PcDocumentHighlightProvider.
   */
  import cp._
  object traverser {

    /**
     * gathers all nodes inside given tree.
     * The nodes have symbol.
     */
    def traverse(
        nodes: Set[NodeInfo],
        tree: cp.Tree
    ): Set[NodeInfo] = {

      tree match {
        /**
         * All indentifiers such as:
         * val a = <<b>>
         */
        case ident: cp.Ident if ident.pos.isRange =>
          nodes + new NodeInfo(ident, ident.pos)
        /**
         * Needed for type trees such as:
         * type A = [<<b>>]
         */
        case tpe: cp.TypeTree if tpe.original != null && tpe.pos.isRange =>
          nodes + new NodeInfo(tpe.original, typePos(tpe))

        /**
         * All select statements such as:
         * val a = hello.<<b>>
         */
        case sel: cp.Select if sel.pos.isRange =>
          traverse(
            nodes + new NodeInfo(sel, sel.namePos),
            sel.qualifier
          )
        /* all definitions:
         * def <<foo>> = ???
         * class <<Foo>> = ???
         * etc.
         */
        case df: cp.MemberDef if df.pos.isRange =>
          (annotationChildren(df) ++ df.children)
            .foldLeft(
              nodes + new NodeInfo(df, df.namePos)
            )(traverse(_, _))
        /* Named parameters, since they don't show up in typed tree:
         * foo(<<name>> = "abc")
         * User(<<name>> = "abc")
         * etc.
         */
        case appl: cp.Apply =>
          val named = appl.args
            .flatMap { arg =>
              namedArgCache.get(arg.pos.start)
            }
            .collectFirst { case cp.AssignOrNamedArg(i @ cp.Ident(_), _) =>
              new NodeInfo(i, i.pos)
            }

          tree.children.foldLeft(nodes ++ named)(traverse(_, _))

        /**
         * We don't automatically traverser types like:
         * val opt: Option[<<String>>] =
         */
        case tpe: cp.TypeTree if tpe.original != null =>
          tpe.original.children.foldLeft(nodes)(traverse(_, _))
        /**
         * Some type trees don't have symbols attached such as:
         * type A = List[_ <: <<Iterable>>[Int]]
         */
        case id: cp.Ident if id.symbol == cp.NoSymbol =>
          fallbackSymbol(id.name, id.pos) match {
            case Some(_) => nodes + new NodeInfo(id, id.pos)
            case _ => nodes
          }

        case df: cp.MemberDef =>
          (tree.children ++ annotationChildren(df))
            .foldLeft(nodes)(traverse(_, _))
        /**
         * For traversing import selectors:
         * import scala.util.<<Try>>
         */
        case imp: cp.Import =>
          nodes + new NodeInfo(imp)

        case _ =>
          if (tree == null) null
          else tree.children.foldLeft(nodes)(traverse(_, _))
      }
    }

    def fallbackSymbol(name: cp.Name, pos: cp.Position): Option[Symbol] = {
      val context = cp.doLocateImportContext(pos)
      context.lookupSymbol(name, sym => sym.isType) match {
        case cp.LookupSucceeded(_, symbol) =>
          Some(symbol)
        case _ => None
      }
    }
    private def annotationChildren(mdef: cp.MemberDef): List[cp.Tree] = {
      mdef.mods.annotations match {
        case Nil if mdef.symbol != null =>
          // After typechecking, annotations are moved from the modifiers
          // to the annotation on the symbol of the annotatee.
          mdef.symbol.annotations.map(_.original)
        case anns => anns
      }
    }

    private def typePos(tpe: cp.TypeTree) = {
      tpe.original match {
        case cp.AppliedTypeTree(tpt, _) => tpt.pos
        case sel: cp.Select => sel.namePos
        case _ => tpe.pos
      }
    }
    // We need to collect named params since they will not show on fully typed tree
    lazy val namedArgCache: Map[Int, NamedArg] = {
      val parsedTree = cp.parseTree(source)
      parsedTree.collect { case arg @ cp.AssignOrNamedArg(_, rhs) =>
        rhs.pos.start -> arg
      }.toMap
    }
  }

  def selector(imp: cp.Import, startOffset: Int): Option[cp.Symbol] = {
    for {
      sel <- imp.selectors.reverseIterator
        .find(_.namePos <= startOffset)
    } yield imp.expr.symbol.info.member(sel.name)
  }
  def pickFromTraversed(tk: scala.meta.tokens.Token): NodeInfo = {
    val buffer = ListBuffer.empty[NodeInfo]

    for (node <- nodes) {
      node.tree match {
        case imp: cp.Import =>
          selector(imp, tk.pos.start) match {
            case Some(sym) => buffer.++=(List(NodeInfo(sym)))
            case None => // pass
          }
        case _ =>
          if (
            node.pos.start == tk.pos.start &&
            node.pos.end == tk.pos.end
            //  node.tree.symbol.name.toString==tk.text
          ) buffer.++=(List(node))
      }
    }

    val nodeList = buffer.toList
    if (nodeList.size == 0) null else nodeList(0)
  }

  /**
   * returns (SemanticTokenType, SemanticTokenModifier) of @param tk
   */
  private def getTypeAndMod(tk: scala.meta.tokens.Token): (Int, Int, String) = {

    // whether token is identifier or not
    tk match {
      case _: Token.Ident | _: Token.Constant.Symbol =>
      // continue this method.
      // Constant.Symbol means literal symbol with backticks.
      // e.g. which is `yield` of such as Thread.`yield`().
      case _ =>
        // Non-Ident has no modifier.
        return (typeOfNonIdentToken(tk), 0, strSep + "Non-Ident")
    }


    val nodeInfo = pickFromTraversed(tk)

    if (nodeInfo == null) { (-1, 0, strSep + "Node-Nothing") }
    else {
      val sym = nodeInfo.symbol

      // Moodifier to return
      var mod: Int = 0
      def addPwrToMod(tokenID: String) = {
        val place: Int = capableModifiers.indexOf(tokenID)
        if (place != -1) mod += (1 << place)
      }

      // get Type
      val typ =
        if (sym.isValueParameter) getTypeId(SemanticTokenTypes.Parameter)
        else if (sym.isTypeParameter)
          getTypeId(SemanticTokenTypes.TypeParameter)
        else
        // See symbol.keystring about following conditions.
        if (sym.isJavaInterface)
          getTypeId(SemanticTokenTypes.Interface) // "interface"
        else if (sym.isTrait) getTypeId(SemanticTokenTypes.Interface) // "trait"
        else if (sym.isClass) getTypeId(SemanticTokenTypes.Class) // "class"
        else if (sym.isType && !sym.isParameter)
          getTypeId(SemanticTokenTypes.Type) // "type"
        else if (sym.isVariable) getTypeId(SemanticTokenTypes.Variable) // "var"
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

      (typ, mod, logString)
    }
  }

  import scala.reflect.internal.util.Position
  private def namePos(t: cp.Tree): Position = {
    try {
      val wkStart = t.pos.point
      val wkEnd = wkStart + t.symbol.name.length() // - 1
      Position.range(t.pos.source, wkStart, wkStart, wkEnd)
    } catch {
      case _ => null
    }
  }

  var counter = 0

  /** makes string to logging tree construction. */
  def treeDescriber(t: cp.Tree, doRecurse: Boolean = true): String = {
    if (t == null) return "  " + "Null Tree"

    var ret = ""
    if (counter == 0 && doRecurse) ret += "\nNodesNum: " + t.id.toString

    counter += 1
    ret += linSep
    ret += "  " + ("000" + counter.toString()).takeRight(3) + "  "

    // Position
    try {
      // ret += "pos(stt,end,point):(" + t.pos.start.toString() + strSep + t.pos.end.toString()
      // ret += strSep + t.pos.point.toString()
      // ret += ")"
      val wkNamePos = namePos(t)
      // ret += strSep
      ret += "namePos:(" + wkNamePos.start.toString()
      // ret += "," + t.symbol.fullName.length()
      ret += "," + wkNamePos.end.toString() + ")"
    } catch { case _ => }
    ret += strSep + "-> TreeCls:" + t.getClass.getName.substring(29)

    // symbol
    try {
      ret = ret + SymDescriber(t.symbol)

    } catch { case _ => return "" }

    //  val wkTreeType  = t match {
    //         case _:Ident => "Ident"
    //         case _:Select => "Select"
    //         case mem:MemberDef => "MemberDef " + mem.keyword
    //         case _:DefTree => "DefTree" //continue
    //         case _:ValDef => "ValDef" //continue
    //         case _:Assign => "Assign" //continue
    //         case appl: Apply => "Assign" //continue
    //         case _ => ""
    //       }
    //   ret += strSep + "TreeTyp:" + wkTreeType
    // ret += strSep + "\n   -> keyword:"+ keyword(t).toString()

    // recursive
    if (doRecurse)
      ret += t.children
        .map(treeDescriber(_, true))
        .mkString("\n")

    // end
    ret + linSep

  }
  def SymDescriber(sym: cp.Symbol): String = {
    var ret = ""

    ret += strSep + "sym:" + sym.toString
    ret += strSep + "keyStr:" + sym.keyString
    ret += strSep + "\n  name:" + sym.nameString
    ret += strSep + "SymCls:" + sym.getClass.getName.substring(31)
    ret += strSep + "SymKnd:" + sym.accurateKindString

    ret

  }

  def tokenDescriber(tk: scala.meta.tokens.Token): String = {

    var logString = ""
    logString += linSep

    logString += "token: " + tk.getClass.toString.substring(29)
    logString += strSep + "text: " + tk.text.toString()
    logString += strSep + "stt,end:(" + tk.pos.start.toString
    logString += strSep + tk.pos.end.toString + ")"
    logString += strSep + "LnStt,End:(" + tk.pos.startLine.toString
    logString += "," + tk.pos.endLine.toString + ")"

    val nodeInfo = pickFromTraversed(tk)
    counter = 0
    // logString +=wkList.map(treeDescriber(_,false)).mkString("")
    if (nodeInfo != null && nodeInfo.symbol != null) {
      logString = logString + SymDescriber(nodeInfo.symbol)
    }

    logString
  }

}
