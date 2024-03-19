package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.InlayHintsParams
import java.util.Optional
import scala.meta.pc.OutlineFiles

case class CompilerInlayHintsParams(
    rangeParams: CompilerRangeParams,
    inferredTypes: Boolean,
    typeParameters: Boolean,
    implicitParameters: Boolean,
    implicitConversions: Boolean,
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
      implicitParameters = implicitParameters
    )
  }

  override def outlineFiles(): Optional[OutlineFiles] = rangeParams.outlineFiles
}
