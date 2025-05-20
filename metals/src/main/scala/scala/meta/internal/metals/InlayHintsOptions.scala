package scala.meta.internal.metals

case class InlayHintsOptions(options: Map[InlayHintsOption, Boolean])
    extends AnyVal {
  def inferredType: Boolean =
    options.getOrElse(InlayHintsOption.InferredType, false)
  def implicitConversions: Boolean =
    options.getOrElse(InlayHintsOption.ImplicitConversions, false)
  def implicitArguments: Boolean =
    options.getOrElse(InlayHintsOption.ImplicitArguments, false)
  def typeParameters: Boolean =
    options.getOrElse(InlayHintsOption.TypeParameters, false)
  def byNameParameters: Boolean =
    options.getOrElse(InlayHintsOption.ByNameParameters, false)
  def namedParameters: Boolean =
    options.getOrElse(InlayHintsOption.NamedParameters, false)
  def hintsInPatternMatch: Boolean =
    options.getOrElse(InlayHintsOption.HintsInPatternMatch, false)
  def transformationIntermediateTypes: Boolean =
    options.getOrElse(InlayHintsOption.TransformationIntermediateTypes, false)
  def areSyntheticsEnabled: Boolean = options.exists(_._2)
}

object InlayHintsOptions {
  def all: InlayHintsOptions = InlayHintsOptions(
    Map(
      InlayHintsOption.InferredType -> true,
      InlayHintsOption.ImplicitConversions -> true,
      InlayHintsOption.ImplicitArguments -> true,
      InlayHintsOption.TypeParameters -> true,
      InlayHintsOption.ByNameParameters -> true,
      InlayHintsOption.HintsInPatternMatch -> true,
      InlayHintsOption.TransformationIntermediateTypes -> true,
    )
  )
}

sealed trait InlayHintsOption
object InlayHintsOption {
  case object InferredType extends InlayHintsOption
  case object ImplicitConversions extends InlayHintsOption
  case object ImplicitArguments extends InlayHintsOption
  case object TypeParameters extends InlayHintsOption
  case object ByNameParameters extends InlayHintsOption
  case object NamedParameters extends InlayHintsOption
  case object TransformationIntermediateTypes extends InlayHintsOption
  case object HintsInPatternMatch extends InlayHintsOption
  def unapply(value: String): Option[InlayHintsOption] =
    StringCase.kebabToCamel(value) match {
      case "inferredTypes" => Some(InferredType)
      case "implicitConversions" => Some(ImplicitConversions)
      case "implicitArguments" => Some(ImplicitArguments)
      case "typeParameters" => Some(TypeParameters)
      case "byNameParameters" => Some(ByNameParameters)
      case "namedParameters" => Some(NamedParameters)
      case "hintsInPatternMatch" => Some(HintsInPatternMatch)
      case "transformationIntermediateTypes" =>
        Some(TransformationIntermediateTypes)
      case _ => None
    }

}
