package scala.meta.metals.compiler

import org.langmeta.io.Classpath
import scala.meta.internal.semanticdb3.SymbolInformation
import scala.meta.metals.Uri
import scala.meta.metals.search.DocumentIndex
import scalafix.internal.util.LazySymbolTable
import scalafix.internal.util.SymbolTable

class SymtabProvider(
    docs: DocumentIndex,
    scalac: ScalacProvider,
    metacp: MetacpProvider
) {
  def symtab(uri: Uri): SymbolTable = {
    val classpath = scalac.configForUri(uri) match {
      case Some(config) =>
        val unprocessed =
          Classpath(config.classDirectory :: config.dependencyClasspath)
        metacp.process(unprocessed)
      case _ =>
        Classpath(Nil)
    }
    val globalSymtab = new LazySymbolTable(classpath)
    new SymbolTable {
      override def info(symbol: String): Option[SymbolInformation] =
        globalSymtab.info(symbol).orElse {
          for {
            doc <- docs.getDocument(uri)
            info <- doc.symbols.find(_.symbol == symbol)
          } yield info
        }
    }
  }
}
