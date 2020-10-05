package scala.meta.internal.decorations

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metap.PrinterSymtab
import scala.meta.internal.semanticdb.Print
import scala.meta.internal.{semanticdb => s}
import scala.meta.metap.Format

object SemanticdbTreePrinter {

  def printSyntheticInfo(
      textDocument: s.TextDocument,
      synthetic: s.Synthetic,
      printSymbol: String => String,
      userConfig: UserConfiguration,
      simple: Boolean
  ): Option[String] = {

    lazy val symtab = PrinterSymtab.fromTextDocument(textDocument)

    def printTree(t: s.Tree): Option[String] =
      t match {
        case s.Tree.Empty => None
        case s.OriginalTree(_) => None
        case s.TypeApplyTree(function, typeArguments) =>
          Some(printTree(function).getOrElse("") + printTypeArgs(typeArguments))
        case s.ApplyTree(function, arguments) =>
          Some(printTree(function).getOrElse("") + printArgs(arguments))
        case s.LiteralTree(constant) =>
          Some(printConstant(constant))
        case s.SelectTree(_, id) =>
          id.flatMap(printTree)
        case s.FunctionTree(parameters, body) =>
          printTree(body).map(printed => printArgs(parameters) + "=>" + printed)
        case s.IdTree(symbol) =>
          Some(printSymbol(symbol))
        case s.MacroExpansionTree(beforeExpansion, _) =>
          printTree(beforeExpansion)
      }

    def printPrefix(t: s.Type): String = {
      printType(t) match {
        case "" => ""
        case s => s"$s."
      }
    }
    def printTypeArgs(typeArgs: Seq[s.Type]): String =
      typeArgs match {
        case Nil => ""
        case _ => typeArgs.map(printType).mkString("[", ", ", "]")
      }

    def printArgs(args: Seq[s.Tree]): String =
      args match {
        case Nil => ""
        case _ => args.flatMap(printTree).mkString("(", ", ", ")")
      }

    def printType(t: s.Type): String =
      t match {
        case s.Type.Empty => ""
        case s.RepeatedType(tpe) =>
          s"${printType(tpe)}*"
        case s.SingleType(prefix, symbol) =>
          s"${printPrefix(prefix)}${printSymbol(symbol)}"
        case s.TypeRef(prefix, symbol, typeArguments) =>
          s"${printPrefix(prefix)}${printSymbol(symbol)}${printTypeArgs(typeArguments)}"
        case s.WithType(types) =>
          types.map(printType).mkString(" with ")
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
          if (simple)
            s"[ ... ] => ${printType(tpe)}"
          else Print.tpe(Format.Detailed, t, symtab)
        case s.ExistentialType(tpe, _) =>
          if (simple)
            s"${printType(tpe)} forSome { ... }"
          else Print.tpe(Format.Detailed, t, symtab)
        case s.StructuralType(tpe, _) =>
          if (simple)
            s" ${printType(tpe)} { ... }"
          else Print.tpe(Format.Detailed, t, symtab)
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

    synthetic.tree match {
      /**
       *  implicit val str = ""
       *  def hello()(implicit a : String)
       *  hello()<<(str)>>
       */
      case tree @ s.ApplyTree(_: s.OriginalTree, _)
          if userConfig.implicitArgumentAnnotationsEnabled =>
        printTree(tree)

      /**
       *  def hello[T](T object) = object
       *  hello<<[String]>>("")
       */
      case tree @ s.TypeApplyTree(_: s.OriginalTree, _)
          if userConfig.typeAnnotationsEnabled =>
        printTree(tree)
      case _ => None
    }
  }
}
