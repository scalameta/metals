package scala.meta.internal.implementation

import java.nio.file.Path

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

class InheritanceContext(
    val findSymbol: String => Option[SymbolInformation],
    inheritance: Map[String, Set[ClassLocation]],
) {

  def allClassSymbols = inheritance.keySet

  def getLocations(symbol: String)(implicit
      ec: ExecutionContext
  ): Future[Set[ClassLocation]] =
    Future.successful(getWorkspaceLocations(symbol))

  protected def getWorkspaceLocations(symbol: String): Set[ClassLocation] =
    inheritance.getOrElse(symbol, Set.empty)

  def withClasspathContext(
      classpathInheritance: Map[String, Set[ClassLocation]]
  ): InheritanceContext = {
    val newInheritance = mutable.Map(inheritance.toSeq: _*)
    for { (symbol, locations) <- classpathInheritance } {
      val newLocations =
        newInheritance.getOrElse(symbol, Set.empty) ++ locations
      newInheritance += symbol -> newLocations
    }
    new InheritanceContext(
      findSymbol,
      newInheritance.toMap,
    )
  }

  def toGlobal(
      compilers: Compilers,
      implementationsInDependencySources: Map[String, Set[ClassLocation]],
  ) = new GlobalInheritanceContext(
    findSymbol,
    compilers,
    implementationsInDependencySources,
    inheritance,
  )
}

class GlobalInheritanceContext(
    override val findSymbol: String => Option[SymbolInformation],
    compilers: Compilers,
    implementationsInDependencySources: Map[String, Set[ClassLocation]],
    localInheritance: Map[String, Set[ClassLocation]],
) extends InheritanceContext(findSymbol, localInheritance) {
  override def getLocations(
      symbol: String
  )(implicit ec: ExecutionContext): Future[Set[ClassLocation]] = {
    val workspaceImplementations = getWorkspaceLocations(symbol)
    // for enum class we resolve all cases as implementations while indexing
    val enumCasesImplementations =
      implementationsInDependencySources.getOrElse(symbol, Set.empty)
    val shortName = symbol.desc.name.value
    val resolveGlobal =
      implementationsInDependencySources
        .getOrElse(shortName, Set.empty)
        .collect { case loc @ ClassLocation(sym, Some(file)) =>
          compilers.findParents(AbsolutePath(file), sym).map { res =>
            Option.when(
              res.exists { sym =>
                def dealiased =
                  ImplementationProvider.dealiasClass(sym, findSymbol)
                sym == symbol || dealiased == symbol
              }
            )(loc)
          }
        }
    Future
      .sequence(resolveGlobal)
      .map { globalImplementations =>
        workspaceImplementations ++ globalImplementations.flatten ++ enumCasesImplementations
      }
  }
}

object InheritanceContext {

  def fromDefinitions(
      findSymbol: String => Option[SymbolInformation],
      localDefinitions: Map[Path, Map[String, Set[ClassLocation]]],
  ): InheritanceContext = {
    val inheritance = mutable.Map
      .empty[String, Set[ClassLocation]]
    for {
      (_, definitions) <- localDefinitions
      (symbol, locations) <- definitions
    } {
      val updated = inheritance.getOrElse(symbol, Set.empty) ++ locations
      inheritance += symbol -> updated
    }
    new InheritanceContext(findSymbol, inheritance.toMap)
  }
}
