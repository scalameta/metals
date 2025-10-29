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
  }
  
  trait KindCodec[A] {
    def encode(kind: Kind): A
    def decode(value: A): Kind
  }
  
  object KindCodec {
    implicit val intCodec: KindCodec[Int] = new KindCodec[Int] {
      def encode(kind: Kind): Int = kind match {
        case Kind.Type => SymbolInformation.Kind.TYPE.value // 7
        case Kind.ImplicitClass => 100 // Custom value, higher than any SymbolInformation.Kind
      }
      
      def decode(value: Int): Kind = value match {
        case 7 => Kind.Type
        case 100 => Kind.ImplicitClass
        case _ => 
          throw new IllegalArgumentException(s"Unsupported value for ToplevelMember.Kind: $value")
      }
    }
    
    implicit class KindCodecOps(kind: Kind) {
      def encode[A](implicit codec: KindCodec[A]): A = codec.encode(kind)
    }
    
    implicit class CodecDecodeOps[A](value: A) {
      def decodeKind(implicit codec: KindCodec[A]): Kind = codec.decode(value)
    }
  }
  
  trait ToLsp[A] {
    def toLsp(value: A): org.eclipse.lsp4j.SymbolKind
  }
  
  object ToLsp {
    implicit val kindToLsp: ToLsp[Kind] = new ToLsp[Kind] {
      def toLsp(kind: Kind): org.eclipse.lsp4j.SymbolKind = kind match {
        case Kind.Type => org.eclipse.lsp4j.SymbolKind.Class
        case Kind.ImplicitClass => org.eclipse.lsp4j.SymbolKind.Class
      }
    }
    
    implicit class ToLspOps[A](value: A)(implicit ev: ToLsp[A]) {
      def toLsp: org.eclipse.lsp4j.SymbolKind = ev.toLsp(value)
    }
  }
}
