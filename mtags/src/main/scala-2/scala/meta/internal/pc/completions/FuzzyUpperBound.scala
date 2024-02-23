package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

trait FuzzyUpperBound { this: MetalsGlobal =>
  implicit class XtensionType(tpe: Type) {
    def fuzzy_<:<(tpeWithParams: TypeWithParams) = {
      def adjustedType =
        performSubstitutions(tpeWithParams.tpe, tpeWithParams.typeParams)

      tpe <:< tpeWithParams.tpe || tpe <:< adjustedType
    }

    private def performSubstitutions(
        tpe: Type,
        typeParams: List[Symbol]
    ): Type = {
      typeParams.find(_ == tpe.typeSymbol) match {
        case Some(tpeDef) =>
          tpeDef.info match {
            case bounds: TypeBounds => BoundedWildcardType(bounds)
            case tpe => tpe
          }
        case None =>
          tpe match {
            case TypeRef(pre, sym, args) =>
              TypeRef(pre, sym, args.map(performSubstitutions(_, typeParams)))
            case t => t
          }
      }
    }
  }

  case class TypeWithParams(tpe: Type, typeParams: List[Symbol])
}
