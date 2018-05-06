package tests.search

import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.inputs._
import scala.meta.metals.Buffers
import scala.meta.metals.Configuration
import scala.meta.metals.Effects
import scala.meta.metals.Uri
import org.langmeta.jsonrpc.JsonRpcClient

import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb._
import tests.CompilerSuite

abstract class BaseIndexTest extends CompilerSuite {
  val buffers = Buffers()
  implicit val client: JsonRpcClient = JsonRpcClient.empty
  val symbols = SymbolIndex(
    PathIO.workingDirectory,
    buffers,
    Observable.now(Configuration())
  )
  def indexDocument(document: m.Document): Effects.IndexSemanticdb =
    symbols.indexDatabase(
      m.Database(document :: Nil).toSchema(PathIO.workingDirectory)
    )
  def indexInput(uri: Uri, code: String): m.Document = {
    buffers.changed(m.Input.VirtualFile(uri.value, code))
    val document =
      InteractiveSemanticdb.toDocument(compiler, code, uri.value, 10000)
    Predef.assert(
      document.messages.isEmpty, {
        val messages = document.messages
          .map(m => m.position.formatMessage(m.severity.toString(), m.text))
          .mkString("\n\n")
        s"Expected no compile errors/warnings, got:\n$messages"
      }
    )
    indexDocument(document)
    document
  }

}
