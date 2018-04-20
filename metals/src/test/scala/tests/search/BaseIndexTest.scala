package tests.search

import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb._
import org.langmeta.jsonrpc.JsonRpcClient
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.inputs._
import scala.meta.metals.Buffers
import scala.meta.metals.Configuration
import scala.meta.metals.Effects
import scala.meta.metals.Uri
import scala.meta.metals.compiler.ClasspathOps
import scala.meta.metals.compiler.CompilerConfig
import scala.meta.metals.compiler.MetacpProvider
import scala.meta.metals.compiler.ScalacProvider
import scala.meta.metals.compiler.SymtabProvider
import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import tests.CompilerSuite

abstract class BaseIndexTest extends CompilerSuite {
  val buffers = Buffers()
  implicit val client: JsonRpcClient = JsonRpcClient.empty
  val symbols = SymbolIndex(
    PathIO.workingDirectory,
    buffers,
    Observable.now(Configuration())
  )

  val thisClasspath = ClasspathOps.getCurrentClasspath
  val scalac = new ScalacProvider()
  scalac.loadNewCompilerGlobals(
    CompilerConfig.empty.copy(
      dependencyClasspath = thisClasspath.shallow,
      managedSourceDirectories = sourceDirectory :: Nil
    )
  )
  val symtabs = new SymtabProvider(
    symbols.documentIndex,
    scalac,
    new MetacpProvider
  )
  def indexDocument(document: m.Document): Effects.IndexSemanticdb = {
    val sdoc = m.Database(document :: Nil).toSchema(PathIO.workingDirectory)
    symbols.indexDatabase(sdoc)
  }
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
