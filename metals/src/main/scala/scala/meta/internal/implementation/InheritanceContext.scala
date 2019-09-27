package scala.meta.internal.implementation
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.symtab.GlobalSymbolTable

trait InheritanceContext {
  def findSymbol(symbol: String): Option[SymbolInformation]
  def typeMapping(symbol: String): Option[String] = None
  def reverseTypeMapping(symbol: String): Option[String] = None
  def inheritance: Map[String, Set[ClassLocation]] = Map.empty
}

case class LocalInheritanceContext(semanticDb: TextDocument)
    extends InheritanceContext {

  def findSymbol(symbol: String): Option[SymbolInformation] = {
    semanticDb.symbols.find(_.symbol == symbol)
  }
}

case class GlobalInheritanceContext(
    symtab: GlobalSymbolTable,
    typeMappings: Map[String, String],
    reverseTypeMappings: Map[String, String],
    override val inheritance: Map[String, Set[ClassLocation]]
) extends InheritanceContext {

  private def dealias(
      typeMap: Map[String, String],
      symbol: String
  ): Option[String] = {
    if (typeMap.contains(symbol)) {
      var sym = symbol
      while (typeMap.contains(sym)) sym = typeMap(sym)
      Some(sym)
    } else None
  }

  override def reverseTypeMapping(symbol: String): Option[String] = {
    dealias(reverseTypeMappings, symbol)
  }

  override def typeMapping(symbol: String): Option[String] = {
    dealias(typeMappings, symbol)
  }

  def findSymbol(symbol: String): Option[SymbolInformation] = {
    symtab.info(symbol)
  }
}
