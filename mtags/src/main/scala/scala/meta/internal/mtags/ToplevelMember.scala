package scala.meta.internal.mtags

import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolInformation

/**
 * Represents a toplevel member in a source file such as `type A = String` in a package or package object.
 * These toplevel members will be indexed in the database.
 *
 * @param symbol The symbol of the toplevel member.
 * @param range The range of the toplevel member in the source file.
 * @param kind The kind of the toplevel member.
 */
case class ToplevelMember(
    symbol: String,
    range: Range,
    kind: SymbolInformation.Kind
)
