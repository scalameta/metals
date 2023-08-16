package scala.meta.internal.metals

import java.net.URI
import java.{util => ju}

import scala.meta.pc.CancelToken
import scala.meta.pc.SyntheticDecorationsParams

import org.eclipse.{lsp4j => l}

case class CompilerSyntheticDecorationsParams(
    rangeParams: CompilerRangeParams,
    withoutTypes: ju.List[l.Range],
    inferredTypes: Boolean,
    implicitParameters: Boolean,
    implicitConversions: Boolean
) extends SyntheticDecorationsParams {
  override def uri(): URI = rangeParams.uri
  override def text(): String = rangeParams.text
  override def token(): CancelToken = rangeParams.token
  override def offset(): Int = rangeParams.offset
  override def endOffset(): Int = rangeParams.endOffset
}
