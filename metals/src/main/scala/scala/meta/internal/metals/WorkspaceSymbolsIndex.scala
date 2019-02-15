package scala.meta.internal.metals

import com.google.common.hash.BloomFilter

/**
 * An index of symbols defined in workspace sources.
 *
 * The path is stored as keys in `WorkspaceSymbolProvider`.
 *
 * @param bloom the `Fuzzy.bloomFilterSymbolStrings` index of all symbols.
 * @param symbols the symbols defined in this source file.
 */
case class WorkspaceSymbolsIndex(
    bloom: BloomFilter[CharSequence],
    // NOTE(olafur) the original plan was to compress these in memory
    // to reduce memory usage but measurements in large repos like akka
    // show that it still uses <5mb in total.
    symbols: Seq[WorkspaceSymbolInformation]
)
