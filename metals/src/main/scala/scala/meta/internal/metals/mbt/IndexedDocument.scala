package scala.meta.internal.metals.mbt

import scala.meta.Dialect
import scala.meta.internal.jmbt.Mbt
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.jsemanticdb.Semanticdb.Language.JAVA
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StringBloomFilter
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.SemanticdbCompilationUnit

import com.google.protobuf.ByteString

/**
 * A sibling Scala data structure for the Protubf message `Mbt.IndexedDocument`,
 * which contains parsed data like a loaded bloom filter and `AbsolutePath`
 * instead of a string URI.
 */
case class IndexedDocument(
    file: AbsolutePath,
    oid: String,
    source: Mbt.IndexedDocument.Source,
    semanticdbPackage: String,
    language: Semanticdb.Language,
    symbols: collection.Seq[Mbt.SymbolInformation],
    bloomFilter: StringBloomFilter,
) {
  def toSemanticdbCompilationUnit(
      buffers: Buffers
  ): SemanticdbCompilationUnit = {
    val toplevelSymbols = symbols.collect {
      case info if Symbol(info.getSymbol()).isToplevel => info.getSymbol()
    }

    VirtualTextDocument(
      file.toURI,
      language.toPCLanguage,
      file.toInputFromBuffers(buffers).text,
      semanticdbPackage,
      toplevelSymbols,
    )
  }

  def toIndexProto(): Mbt.Index = {
    Mbt.Index
      .newBuilder()
      .addDocuments(toProto())
      .build()
  }
  def toProto(): Mbt.IndexedDocument.Builder = {
    Mbt.IndexedDocument
      .newBuilder()
      .setUri(file.toURI.toString)
      .setOid(oid)
      .setSource(source)
      .setSemanticdbPackage(semanticdbPackage)
      .setLanguage(language)
      .addAllSymbols(symbols.asJava)
      .setBloomFilter(ByteString.copyFrom(bloomFilter.toBytes))
      .setBloomFilterVersion(
        if (language.isJava) Mbt.IndexedDocument.BloomFilterVersion.V2
        else Mbt.IndexedDocument.BloomFilterVersion.V1
      )
  }
}

object IndexedDocument {

  /**
   * Indexes a file by running mtags against its text contents (either in
   * Buffers or from disk)
   */
  def fromFile(
      file: AbsolutePath,
      mtags: Mtags,
      buffers: Buffers,
      dialect: Dialect,
  ): IndexedDocument = {
    val input = file.toInputFromBuffers(buffers)
    val sdoc = mtags.indexToplevelSymbols(
      file.toNIO.toJLanguage,
      input,
      dialect,
    )
    val symbols = for {
      info <- sdoc.symbols.iterator
      if !info.kind.isPackage
      occ <- sdoc.occurrences.find(_.symbol == info.symbol)
      range <- occ.range
    } yield Mbt.SymbolInformation
      .newBuilder()
      .setSymbol(info.symbol)
      .setKind(info.kind.toJKind)
      .setDefinitionRange(range.toJRange)
      .build()

    val semanticdbPackage = sdoc.occurrences.iterator
      .filter(_.symbol.endsWith("/"))
      .maxByOption(_.symbol.length)
      .map(_.symbol)
      .getOrElse(Symbol.EmptyPackage.value)
    IndexedDocument(
      file = file,
      oid = OID.fromText(input.text),
      source = Mbt.IndexedDocument.Source.ON_DID_CHANGE_FILE,
      semanticdbPackage = semanticdbPackage,
      bloomFilter =
        Fuzzy.bloomFilterSymbolStrings(sdoc.occurrences.map(_.symbol)),
      language = file.toJLanguage,
      symbols = symbols.toSeq,
    )
  }

  /**
   * Uses existing mtags-indexed symbols to create an IndexedDocument.
   */
  def fromOnDidChangeParams(
      params: OnDidChangeSymbolsParams
  ): IndexedDocument = {
    val semanticdbPackage: String = params.symbols.headOption match {
      case None => Symbol.EmptyPackage.value
      case Some(info) => Symbol(info.symbol).enclosingPackage.value
    }
    IndexedDocument(
      file = params.path,
      oid = OID.fromText(params.input.text),
      source = Mbt.IndexedDocument.Source.ON_DID_CHANGE_SYMBOLS,
      semanticdbPackage = semanticdbPackage,
      bloomFilter =
        Fuzzy.bloomFilterSymbolStrings(params.symbols.map(_.symbol)),
      language = params.path.toJLanguage,
      symbols = params.symbols
        .map(s =>
          Mbt.SymbolInformation
            .newBuilder()
            .setSymbol(s.symbol)
            .setKind(s.sematicdbKind.toJKind)
            .setDefinitionRange(
              Semanticdb.Range
                .newBuilder()
                .setStartLine(s.range.getStart().getLine())
                .setStartCharacter(s.range.getStart().getCharacter())
                .setEndLine(s.range.getEnd().getLine())
                .setEndCharacter(s.range.getEnd().getCharacter())
                .build()
            )
            .build()
        )
        .toSeq,
    )
  }

  /**
   * Converts a Protobuf `Mbt.IndexedDocument` into a Scala `IndexedDocument`.
   */
  def fromProto(
      path: AbsolutePath,
      doc: Mbt.IndexedDocument,
  ): IndexedDocument = {
    IndexedDocument(
      file = path,
      oid = doc.getOid(),
      source = doc.getSource(),
      semanticdbPackage = doc.getSemanticdbPackage(),
      language = doc.getLanguage(),
      symbols = doc.getSymbolsList().asScala,
      bloomFilter =
        StringBloomFilter.fromBytes(doc.getBloomFilter().toByteArray),
    )
  }

  // Bump up this version when we make changes to what kinds of strings we
  // insert into the bloom filter. For example, if we change mtags for a
  // specific language, then we should bump the version requirement for
  // that language.
  def matchesCurrentVersion(doc: Mbt.IndexedDocument): Boolean =
    doc.getBloomFilterVersion().getNumber >= (doc.getLanguage() match {
      case JAVA => Mbt.IndexedDocument.BloomFilterVersion.V2
      case _ => Mbt.IndexedDocument.BloomFilterVersion.V1
    }).getNumber()
}
