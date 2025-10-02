package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.SyntheticDecorationsParams
import scala.meta.pc.VirtualFileParams

case class CompilerSyntheticDecorationsParams(
    virtualFileParams: VirtualFileParams,
    inferredTypes: Boolean,
    typeParameters: Boolean,
    implicitParameters: Boolean,
    hintsXRayMode: Boolean,
    byNameParameters: Boolean,
    implicitConversions: Boolean,
    override val closingLabels: Boolean
) extends SyntheticDecorationsParams {
  override def uri(): URI = virtualFileParams.uri
  override def text(): String = virtualFileParams.text
  override def token(): CancelToken = virtualFileParams.token
}
