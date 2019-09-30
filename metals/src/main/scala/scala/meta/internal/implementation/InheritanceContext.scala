package scala.meta.internal.implementation
import scala.meta.internal.semanticdb.SymbolInformation
import java.nio.file.Path
import scala.collection.mutable

case class LocalClassDefinitions(
    inheritance: Map[String, Set[ClassLocation]]
)

case class InheritanceContext(
    findSymbol: String => Option[SymbolInformation],
    private val inheritance: Map[String, Set[ClassLocation]]
) {
  def allClassSymbols = inheritance.keySet

  def getLocations(symbol: String): Set[ClassLocation] = {
    inheritance.getOrElse(symbol, Set.empty)
  }

  def withClasspathContext(
      classpathInheritance: Map[String, Set[ClassLocation]],
      findGlobalSymbol: String => Option[SymbolInformation]
  ): InheritanceContext = {
    val newInheritance = mutable.Map
      .empty[String, Set[ClassLocation]] ++ inheritance
    for { (symbol, locations) <- classpathInheritance } {
      val newLocations = newInheritance.getOrElse(symbol, Set.empty) ++ locations
      newInheritance += symbol -> newLocations
    }
    this.copy(
      findSymbol = findGlobalSymbol,
      inheritance = newInheritance.toMap
    )
  }
}

object InheritanceContext {

  def fromDefinitions(
      findSymbol: String => Option[SymbolInformation],
      localDefinitions: Map[Path, LocalClassDefinitions]
  ): InheritanceContext = {
    val inheritance = mutable.Map
      .empty[String, Set[ClassLocation]]
    for {
      (_, definitions) <- localDefinitions
      (symbol, locations) <- definitions.inheritance
    } {
      val updated = inheritance.getOrElse(symbol, Set.empty) ++ locations
      inheritance += symbol -> updated
    }
    InheritanceContext(findSymbol, inheritance.toMap)
  }
}
