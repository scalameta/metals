package scala.meta.internal.metals
import scala.meta.internal.semanticdb.SymbolOccurrence

object ClassPath {
  final case class TypeSignature(value: String)

  def toTypeSignature(definition: SymbolOccurrence): TypeSignature = {
    import scala.meta.internal.semanticdb.Scala._
    @scala.annotation.tailrec
    def qualifier(symbol: String, acc: List[String]): String = {
      val owner = symbol.owner
      if (owner == Symbols.RootPackage || owner == Symbols.EmptyPackage) {
        acc.mkString
      } else {
        val desc = owner.desc
        // assumption: can only be a package or type
        val delimiter = if (desc.isPackage) "." else "$"
        val segment = desc.name + delimiter
        qualifier(owner, segment :: acc)
      }
    }

    def name(symbol: String): String = {
      val desc = symbol.desc
      val name = desc.name

      val suffix = if (desc.isTerm) "$" else ""
      name + suffix
    }

    val symbol = definition.symbol
    val fqcn = qualifier(symbol, Nil) + name(symbol)
    TypeSignature(fqcn)
  }
}
