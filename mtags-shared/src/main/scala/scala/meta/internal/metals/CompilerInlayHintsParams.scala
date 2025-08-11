package scala.meta.internal.metals

import java.net.URI
import java.util.Optional

import scala.meta.pc.CancelToken
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.OutlineFiles

case class CompilerInlayHintsParams(
    rangeParams: CompilerRangeParams,
    inferredTypes: Boolean,
    typeParameters: Boolean,
    implicitParameters: Boolean,
    override val hintsXRayMode: Boolean,
    override val byNameParameters: Boolean,
    implicitConversions: Boolean,
    override val namedParameters: Boolean,
    override val hintsInPatternMatch: Boolean
) extends InlayHintsParams {
  override def uri(): URI = rangeParams.uri
  override def text(): String = rangeParams.text
  override def token(): CancelToken = rangeParams.token
  override def offset(): Int = rangeParams.offset
  override def endOffset(): Int = rangeParams.endOffset

  def toSyntheticDecorationsParams: CompilerSyntheticDecorationsParams = {
    CompilerSyntheticDecorationsParams(
      rangeParams,
      inferredTypes = inferredTypes,
      typeParameters = typeParameters,
      implicitConversions = implicitConversions,
      implicitParameters = implicitParameters,
      hintsXRayMode = hintsXRayMode,
      byNameParameters = byNameParameters
    )
  }

  override def outlineFiles(): Optional[OutlineFiles] = rangeParams.outlineFiles
}
