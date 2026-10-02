package scala.meta.internal.metals

case class InlayHintsOptions(
    inferredType: Boolean = false,
    implicitConversions: Boolean = false,
    implicitArguments: Boolean = false,
    typeParameters: Boolean = false,
    byNameParameters: Boolean = false,
    namedParameters: Boolean = false,
    hintsInPatternMatch: Boolean,
    hintsXRayMode: Boolean = false,
    closingLabels: Boolean = false,
) {

  def areSyntheticsEnabled(): Boolean =
    inferredType || implicitConversions || implicitArguments ||
      typeParameters || byNameParameters || namedParameters ||
      hintsInPatternMatch || hintsXRayMode || closingLabels
}

object InlayHintsOptions {
  val none: InlayHintsOptions = InlayHintsOptions(
    inferredType = false,
    implicitConversions = false,
    implicitArguments = false,
    typeParameters = false,
    byNameParameters = false,
    namedParameters = false,
    hintsInPatternMatch = false,
    hintsXRayMode = false,
    closingLabels = false,
  )

}
