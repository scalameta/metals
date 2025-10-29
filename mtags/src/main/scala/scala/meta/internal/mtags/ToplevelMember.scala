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
    kind: ToplevelMember.Kind
)

object ToplevelMember {
  
  sealed trait Kind
  
  object Kind {
    case object Type extends Kind
    case object ImplicitClass extends Kind
    
    private val idMapping: Map[Kind, Int] = Map(
      Type -> SymbolInformation.Kind.TYPE.value, // 7
      ImplicitClass -> 100 // Custom value, higher than any SymbolInformation.Kind
    )
    
    private val reverseMapping: Map[Int, Kind] = idMapping.map(_.swap)
    
    private val lspMapping: Map[Kind, org.eclipse.lsp4j.SymbolKind] = Map(
      Type -> org.eclipse.lsp4j.SymbolKind.Class,
      ImplicitClass -> org.eclipse.lsp4j.SymbolKind.Class
    )
    
    def fromId(id: Int): Kind = reverseMapping.getOrElse(
      id,
      throw new IllegalArgumentException(s"Unsupported id for ToplevelMember.Kind: $id")
    )
    
    implicit class KindOps(val kind: Kind) extends AnyVal {
      def toId: Int = idMapping(kind)
      def toLsp: org.eclipse.lsp4j.SymbolKind = lspMapping(kind)
    }
  }
}
