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
    
    private final val ImplicitClassId = 100 // Custom value, higher than any SymbolInformation.Kind
    
    def fromId(id: Int): Kind = id match {
      case SymbolInformation.Kind.TYPE.value => Type // Trying to keep compatibility with old ids
      case ImplicitClassId => ImplicitClass
      case _ =>
        throw new IllegalArgumentException(s"Unsupported id for ToplevelMember.Kind: $id")
    }
    
    implicit class KindOps(val kind: Kind) {
      def toId: Int = kind match {
        case Type => SymbolInformation.Kind.TYPE.value // Trying to keep compatibility with old ids
        case ImplicitClass => ImplicitClassId
      }
      
      def toLsp: org.eclipse.lsp4j.SymbolKind = kind match {
        case Type => org.eclipse.lsp4j.SymbolKind.Class
        case ImplicitClass => org.eclipse.lsp4j.SymbolKind.Class
      }
    }
  }
}
