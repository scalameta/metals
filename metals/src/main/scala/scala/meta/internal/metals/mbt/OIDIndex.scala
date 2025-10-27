package scala.meta.internal.metals.mbt

import java.nio.file.Path

import scala.collection.mutable

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.StringBloomFilter

case class OIDIndex(
    language: Semanticdb.Language,
    oid: Array[Byte],
    // Not used for anything except debugging. It's tricky to troubleshoot
    // when you only have a random git OID.
    path: Path,
    // The set of all SemanticDB symbols that are *defined* in this file. For
    // example, the symbol "scala/metals/Main.main()." for the file
    // "metals/src/main/scala/scala/meta/metals/Main.scala". Importantly, you
    // must use WorkspaceSymbolQuery to search in this set, it doesn't
    // actually include the full symbol (yet), it only includes prefixes of
    // different parts of the symbol.
    documentSymbols: StringBloomFilter,
    semanticdbPackage: String,
    toplevelSymbols: mutable.ArrayBuffer[String],
)
