package scala.meta.internal.pc

import java.{util => ju}

import scala.collection.JavaConverters._

import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

case class PcReferencesRequest(
    file: VirtualFileParams,
    includeDefinition: Boolean,
    offsetOrSymbol: JEither[Integer, String],
    override val alternativeSymbols: ju.List[String] = Nil.asJava
) extends ReferencesRequest

case class PcReferencesResult(
    symbol: String,
    locations: ju.List[Location]
) extends ReferencesResult

object PcReferencesResult {
  def empty: ReferencesResult =
    PcReferencesResult("", ju.Collections.emptyList())
}
