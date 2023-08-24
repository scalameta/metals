package scala.meta.internal.metals

import java.net.URI
import java.{util => ju}

import scala.meta.pc.CancelToken
import scala.meta.pc.SyntheticDecorationsParams

import org.eclipse.{lsp4j => l}

case class CompilerSyntheticDecorationsParams(
    vFileParams: CompilerVirtualFileParams,
    withoutTypes: ju.List[l.Range],
    inferredTypes: Boolean,
    implicitParameters: Boolean,
    implicitConversions: Boolean
) extends SyntheticDecorationsParams {
  override def uri(): URI = vFileParams.uri
  override def text(): String = vFileParams.text
  override def token(): CancelToken = vFileParams.token
}
