package scala.meta.internal.metals.mbt

import java.util.concurrent.atomic.AtomicReference
import javax.tools.JavaFileObject

import scala.collection.View

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.jmbt.Mbt
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.jsemanticdb.Semanticdb.Language
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.FingerprintedCharSequence
import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StringBloomFilter
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.proto.ProtoMtagsV2
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
    semanticdbPackages: Seq[String],
    language: Semanticdb.Language,
    symbols: collection.Seq[Mbt.SymbolInformation],
    bloomFilter: StringBloomFilter,
) {
  // Cached generated Java outlines from proto files.
  // This is lazily populated and automatically invalidated when
  // the proto file changes (new IndexedDocument replaces old one).
  private val cachedProtobufJavaOutlines
      : AtomicReference[Seq[VirtualTextDocument]] =
    new AtomicReference(null)

  def getOrComputeJavaOutlines(
      compute: () => Seq[VirtualTextDocument]
  ): Seq[VirtualTextDocument] = {
    var result = cachedProtobufJavaOutlines.get()
    if (result == null) {
      val computed = compute()
      cachedProtobufJavaOutlines.compareAndSet(null, computed)
      result = cachedProtobufJavaOutlines.get()
    }
    result
  }

  def clearProtobufJavaOutlinesCache(): Unit = {
    cachedProtobufJavaOutlines.set(null)
  }

  def toSemanticdbCompilationUnit(
      input: Input.VirtualFile
  ): SemanticdbCompilationUnit with JavaFileObject = {
    val toplevelSymbols = symbols.collect {
      case info if Symbol(info.getSymbol()).isToplevel => info.getSymbol()
    }

    VirtualTextDocument(
      SourceJavaFileObject.makeRelativeURI(file.toURI),
      language.toPCLanguage,
      input.text,
      semanticdbPackages,
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
    val bloomFilterVersion = language match {
      case Language.JAVA => Mbt.IndexedDocument.BloomFilterVersion.V7
      case Language.PROTOBUF => Mbt.IndexedDocument.BloomFilterVersion.V9
      case _ => Mbt.IndexedDocument.BloomFilterVersion.V6
    }
    Mbt.IndexedDocument
      .newBuilder()
      .setUri(file.toURI.toString)
      .setOid(oid)
      .setSource(source)
      .addAllSemanticdbPackage(semanticdbPackages.asJava)
      .setLanguage(language)
      .addAllSymbols(symbols.asJava)
      .setBloomFilter(ByteString.copyFrom(bloomFilter.toBytes))
      .setBloomFilterVersion(bloomFilterVersion)
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
      enableProtoJavaPackage: Boolean = false,
  ): IndexedDocument = {
    val input = file.toInputFromBuffers(buffers)
    val sdoc = mtags.indexMBT(
      file.toNIO.toJLanguage,
      input,
      dialect,
      includeReferences = true,
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

    val definitions =
      sdoc.occurrences.view.filter(_.role.isDefinition).map(_.symbol)
    val references =
      sdoc.occurrences.view.filter(_.role.isReference).map(_.symbol)

    // For proto files, use ProtoMtagsV2 to get both proto package and java_package.
    // This is needed because the default mtags indexer (V1) only indexes by proto package.
    // ProtoMtagsV2 parses the proto file and extracts java_package from options.
    val semanticdbPackages =
      if (enableProtoJavaPackage && file.isProto) {
        try {
          val protoMtags = new ProtoMtagsV2(input, includeMembers = false)
          // Trigger parsing by accessing semanticdbPackages
          protoMtags.semanticdbPackages
        } catch {
          case _: Exception =>
            // Fall back to packages from sdoc if parsing fails
            sdoc.semanticdbPackages
        }
      } else sdoc.semanticdbPackages

    IndexedDocument(
      file = file,
      oid = OID.fromText(input.text),
      source = Mbt.IndexedDocument.Source.ON_DID_CHANGE_FILE,
      semanticdbPackages,
      bloomFilter = bloomFilterMBT(definitions, references),
      language = file.toJLanguage,
      symbols = symbols.toSeq,
    )
  }

  // Minimum bloom filter size to avoid sensitivity issues with very small filters.
  // Small bloom filters (e.g., size=6) are extremely sensitive - adding 1 extra
  // entry can push the FPP from 1% to 1.5%. Using a minimum threshold makes small
  // files more robust to estimation inaccuracies.
  private val MinBloomFilterSize = 256

  private def bloomFilterMBT(
      definitions: View[String],
      references: View[String],
  ): StringBloomFilter = {
    val symbolsSize = Fuzzy.estimateSizeOfSymbolStrings(definitions)
    val estimatedSize =
      math.max(MinBloomFilterSize, symbolsSize + references.size)
    val bf = StringBloomFilter.forEstimatedSize(estimatedSize)
    Fuzzy.bloomFilterSymbolStrings(definitions, bf)
    references.foreach { sym =>
      bf.putCharSequence(FingerprintedCharSequence.fuzzyReference(sym))
    }
    bf
  }

  /**
   * Uses existing mtags-indexed symbols to create an IndexedDocument.
   */
  def fromOnDidChangeParams(
      params: OnDidChangeSymbolsParams
  ): IndexedDocument = {

    import scala.meta.internal.mtags.Symbol

    val toplevels =
      params.symbols.map(info => Symbol(info.symbol)).filter(_.isToplevel)
    val semanticdbPackages = toplevels.map(_.owner).toSet match {
      case s if s.isEmpty => List(Symbol.EmptyPackage.value)
      case s => s.map(_.value).toList
    }

    IndexedDocument(
      file = params.path,
      oid = OID.fromText(params.input.text),
      source = Mbt.IndexedDocument.Source.ON_DID_CHANGE_SYMBOLS,
      semanticdbPackages.distinct,
      bloomFilter = bloomFilterMBT(
        params.symbols.view.map(_.symbol),
        params.references.view,
      ),
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
      doc.getSemanticdbPackageList().asScala.toList,
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
      case Language.JAVA => Mbt.IndexedDocument.BloomFilterVersion.V7
      case Language.SCALA => Mbt.IndexedDocument.BloomFilterVersion.V6
      // V9: Proto bloom filters include scanner fixes for option blocks.
      case Language.PROTOBUF => Mbt.IndexedDocument.BloomFilterVersion.V9
      case _ => Mbt.IndexedDocument.BloomFilterVersion.V1
    }).getNumber()
}
