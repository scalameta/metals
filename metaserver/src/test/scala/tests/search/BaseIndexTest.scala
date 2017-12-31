package tests.search

import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.inputs._
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Configuration
import scala.meta.languageserver.Effects
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scala.{meta => m}
import langserver.core.Notifications
import monix.eval.Task
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb._
import tests.compiler.CompilerSuite

abstract class BaseIndexTest extends CompilerSuite {
  val buffers = Buffers()
  val symbols = SymbolIndex(
    PathIO.workingDirectory,
    Notifications.empty,
    buffers,
    Task.now(Configuration())
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
