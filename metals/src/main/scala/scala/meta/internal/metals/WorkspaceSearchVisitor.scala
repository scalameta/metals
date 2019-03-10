package scala.meta.internal.metals

import java.nio.file.Path
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearchVisitor

/**
 * A symbol search visitor for `workspace/symbol`.
 *
 * - workspace symbols are converted directly to l.SymbolInformation
 * - classpath symbols are converted into "goto definition" requests,
 *   which creates files on disk, and then into l.SymbolInformation.
 */
class WorkspaceSearchVisitor(
    query: WorkspaceSymbolQuery,
    token: CancelChecker,
    index: OnDemandSymbolIndex,
    fileOnDisk: AbsolutePath => AbsolutePath
) extends SymbolSearchVisitor {
  val results = ArrayBuffer.empty[l.SymbolInformation]
  val isVisited = mutable.Set.empty[AbsolutePath]
  def definition(
      pkg: String,
      filename: String,
      index: OnDemandSymbolIndex
  ): Option[SymbolDefinition] = {
    val nme = Classfile.name(filename)
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    index.definition(tpe).orElse {
      val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
      index.definition(term)
    }
  }
  override def shouldVisitPackage(pkg: String): Boolean = true
  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: l.Range
  ): Int = {
    val (desc, owner) = DescriptorParser(symbol)
    results += new l.SymbolInformation(
      desc.name.value,
      kind,
      new l.Location(path.toUri.toString, range),
      owner.replace('/', '.')
    )
    1
  }
  override def visitClassfile(pkg: String, filename: String): Int = {
    var isHit = false
    for {
      defn <- definition(pkg, filename, index)
      if !isVisited(defn.path)
    } {
      isVisited += defn.path
      val input = defn.path.toInput
      lazy val uri = fileOnDisk(defn.path).toURI.toString
      SemanticdbDefinition.foreach(input) { defn =>
        if (query.matches(defn.info)) {
          results += defn.toLSP(uri)
          isHit = true
        }
      }
    }
    if (isHit) 1 else 0
  }
  override def isCancelled: Boolean = token.isCancelled
}
