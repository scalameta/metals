package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.SyntheticDecorationsParams

case class CompilerSyntheticDecorationsParams(
    vFileParams: CompilerVirtualFileParams,
    inferredTypes: Boolean,
    typeParameters: Boolean,
    implicitParameters: Boolean,
    implicitConversions: Boolean
) extends SyntheticDecorationsParams {
  override def uri(): URI = vFileParams.uri
  override def text(): String = vFileParams.text
  override def token(): CancelToken = vFileParams.token
}
