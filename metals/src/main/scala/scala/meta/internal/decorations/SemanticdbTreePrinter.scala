package scala.meta.internal.decorations

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metap.PrinterSymtab
import scala.meta.internal.semanticdb.Print
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.{semanticdb => s}
import scala.meta.metap.Format

class SemanticdbTreePrinter(
    isHover: Boolean,
    printSymbol: String => String,
    createSymtab: => PrinterSymtab,
    rightArrow: String,
) {

  lazy val symtab = createSymtab

  val nonPrintable: Set[String] = Set("apply", "unapply", "unapplySeq")

  def printType(t: s.Type): String =
    t match {
      case s.Type.Empty => ""
      case s.RepeatedType(tpe) =>
        s"${printType(tpe)}*"
      case s.SingleType(prefix, symbol) =>
        s"${printPrefix(prefix)}${printSymbol(symbol)}"
      case s.TypeRef(prefix, symbol, typeArguments) =>
        val tuple = isTuple(symbol)
        val isFunction = symbol.startsWith("scala/Function")
        val sym =
          if (tuple || isFunction) ""
          else printSymbol(symbol)
        val typeArgs = printTypeArgs(typeArguments, tuple, isFunction)
        s"${printPrefix(prefix)}${sym}${typeArgs}"
      case s.WithType(types) =>
        val simpleTypes = types.dropWhile {
          case s.TypeRef(_, sym, _) =>
            sym == "scala/AnyRef#" || sym == "java/lang/Object#"
          case _ => false
        }
        simpleTypes.map(printType).mkString(" with ")
      case s.ConstantType(constant) =>
        printConstant(constant)
      case s.ByNameType(tpe) =>
        s"=> ${printType(tpe)}"
      case s.ThisType(symbol) =>
        s"this.${printSymbol(symbol)}"
      case s.IntersectionType(types) =>
        types.map(printType).mkString(" & ")
      case s.UnionType(types) =>
        types.map(printType).mkString(" | ")
      case s.SuperType(prefix, symbol) =>
        s"super.${printPrefix(prefix)}${printSymbol(symbol)}"
      case s.AnnotatedType(annots, tp) =>
        val mapped = annots
          .map(x => s"@${printType(x.tpe)}")
          .reduceLeft((x, y) => s"$x $y")
        s"$mapped ${printType(tp)}"
      // this should not need to be printed but just in case we revert to semanticdb printer
      case s.UniversalType(_, tpe) =>
        if (isHover)
          Print.tpe(Format.Detailed, t, symtab)
        /* We don't want to print the full [x] => F[x]
           because that cannot show up in the actual code
         */
        else {
          tpe match {
            case t: s.TypeRef =>
              printSymbol(t.symbol)
            case tpe =>
              printType(tpe)
          }
        }
      case s.ExistentialType(tpe, _) =>
        if (isHover)
          Print.tpe(Format.Detailed, t, symtab)
        else
          s"${printType(tpe)}"
      case s.StructuralType(tpe, scope) =>
        if (isHover) {
          Print.tpe(Format.Detailed, t, symtab)
        } else {
          s"${printType(tpe)} {${printScope(scope)}}"
        }
      case s.MatchType(scrutinee, cases) =>
        s"${printType(scrutinee)} match { ${cases.size} cases }"
      case s.LambdaType(scope, returnType) =>
        val params = scope.map(scopeInfo).getOrElse(Nil).map(_.displayName)
        params match {
          case Nil =>
            s"=>> ${printType(returnType)}"
          case _ =>
            s"${params.mkString("[", ", ", "]")} =>> ${printType(returnType)}"
        }
    }

  private def scopeInfo(scope: s.Scope): List[s.SymbolInformation] =
    if (scope.symlinks.nonEmpty)
      scope.symlinks.map(symbol => s.SymbolInformation(symbol = symbol)).toList
    else
      scope.hardlinks.toList

  private def printScope(scope: Option[s.Scope]): String = {
    if (scope.exists(_.hardlinks.nonEmpty)) "..." else ""
  }

  def printPrefix(t: s.Type): String = {
    printType(t) match {
      case "" => ""
      case s => s"$s."
    }
  }

  def printTypeArgs(
      typeArgs: Seq[s.Type],
      isTuple: Boolean = false,
      isFunction: Boolean = false,
  ): String =
    typeArgs match {
      case Nil => ""
      case _ if isTuple =>
        typeArgs.map(printType).mkString("(", ", ", ")")
      case _ if isFunction =>
        val argTypes :+ returnType = typeArgs.map(printType)
        argTypes.mkString("(", ", ", ")") + s" $rightArrow $returnType"
      case _ =>
        typeArgs.map(printType).mkString("[", ", ", "]")
    }

  def printArgs(args: Seq[s.Tree]): String =
    args match {
      case Nil => ""
      case _ => args.flatMap(printTree(_)).mkString("(", ", ", ")")
    }

  def printConstant(c: s.Constant): String =
    c match {
      case s.FloatConstant(value) => value.toString
      case s.LongConstant(value) => value.toString
      case s.DoubleConstant(value) => value.toString
      case s.NullConstant() => "null"
      case s.IntConstant(value) => value.toString
      case s.CharConstant(value) => value.toString
      case s.ByteConstant(value) => value.toString
      case s.UnitConstant() => "()"
      case s.ShortConstant(value) => value.toString
      case s.Constant.Empty => ""
      case s.BooleanConstant(value) => value.toString
      case s.StringConstant(value) => value
    }

  def printTree(
      t: s.Tree,
      isExplicitTuple: => Boolean = false,
  ): Option[String] =
    t match {
      case s.Tree.Empty => None
      case s.OriginalTree(_) => None
      case s.TypeApplyTree(function, typeArguments)
          // only print type parameters for tuple if it's not a tuple literal
          if isNotTupleTree(function) || isExplicitTuple =>
        Some(
          printTree(function)
            .filterNot(nonPrintable)
            .getOrElse("") + printTypeArgs(typeArguments)
        )
      case s.ApplyTree(function, arguments) =>
        Some(printTree(function).getOrElse("") + printArgs(arguments))
      case s.LiteralTree(constant) =>
        Some(printConstant(constant))
      case s.SelectTree(_, id) =>
        id.flatMap(printTree(_))
      case s.FunctionTree(parameters, body) =>
        printTree(body).map(printed => printArgs(parameters) + "=>" + printed)
      case s.IdTree(symbol) =>
        Some(printSymbol(symbol))
      case s.MacroExpansionTree(beforeExpansion, _) =>
        printTree(beforeExpansion)
      case _ => None
    }

  def printSyntheticInfo(
      textDocument: s.TextDocument,
      synthetic: s.Synthetic,
      userConfig: UserConfiguration,
      isInlineProvider: Boolean = false,
  ): Seq[(String, s.Range)] = {

    def isExplicitTuple(range: s.Range) =
      range.inString(textDocument.text).exists(_.startsWith("Tuple"))

    def gatherSynthetics(tree: s.Tree, treeRange: Option[s.Range] = None) = {
      for {
        range <- treeRange.orElse(synthetic.range).toList
        syntheticString <- printTree(tree, isExplicitTuple(range)).toList
      } yield (syntheticString, range)
    }

    /* We don't want type trees for anything than toplevel,
     * since that might actually be contained in for comprehension.
     * `ignoreTypesTrees` is only set to false for toplevel synthetics.
     */
    def tryTree(
        tree: s.Tree,
        ignoreTypesTrees: Boolean = true,
    ): Seq[(String, s.Range)] =
      tree match {
        /**
         *  implicit val str = ""
         *  def hello()(implicit a : String)
         *  hello()<<(str)>>
         */
        case tree @ s.ApplyTree(org: s.OriginalTree, _)
            if userConfig.showImplicitArguments =>
          gatherSynthetics(tree, org.range)

        /**
         *  def hello[T](T object) = object
         *  hello<<[String]>>("")
         */
        case tree @ s.TypeApplyTree(_: s.OriginalTree | _: s.SelectTree, _)
            if !ignoreTypesTrees && userConfig.showInferredType =>
          gatherSynthetics(tree)
        /**
         *  implicit def implicitFun(object: T): R = ???
         *  def fun(r: R) = ???
         *  fun(<<implicitFun(>>new T<<)>>)
         */
        case s.ApplyTree(id: s.IdTree, _)
            if userConfig.showImplicitConversionsAndClasses =>
          def synthetics(syntheticString: String, range: s.Range) = {
            if (isHover && isInlineProvider)
              List(
                (
                  syntheticString,
                  range
                    .withEndCharacter(range.startCharacter)
                    .withEndLine(range.startLine),
                )
              )
            else
              List(
                (
                  syntheticString + "(",
                  range
                    .withEndCharacter(range.startCharacter)
                    .withEndLine(range.startLine),
                ),
                (")", range),
              )
          }
          for {
            syntheticString <- printTree(id).toList
            range <- synthetic.range.toList
            synth <- synthetics(syntheticString, range)
          } yield synth

        // needed in case of synthetics inside of more complex synthetic trees such as for comprehensions
        case appl: s.ApplyTree =>
          (appl.arguments :+ appl.function).flatMap(tryTree(_))
        case tree: s.TypeApplyTree =>
          tryTree(tree.function)
        case sel: s.SelectTree =>
          tryTree(sel.qualifier)
        case s.FunctionTree(_, body) =>
          tryTree(body)
        case _ => Nil
      }
    tryTree(synthetic.tree, ignoreTypesTrees = false)
  }

  private def isTuple(symbol: String) = symbol.startsWith("scala/Tuple")

  private def isNotTupleTree(function: s.Tree) = function match {
    case SelectTree(_, Some(id)) => !isTuple(id.symbol)
    case _ => true
  }
}
