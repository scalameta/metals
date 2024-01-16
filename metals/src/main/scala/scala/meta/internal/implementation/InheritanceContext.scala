package scala.meta.internal.implementation

import java.nio.file.Path

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath

class InheritanceContext(inheritance: Map[String, Set[ClassLocation]]) {

  def allClassSymbols = inheritance.keySet

  def getLocations(symbol: String)(implicit
      ec: ExecutionContext
  ): Future[Set[ClassLocation]] =
    Future.successful(getWorkspaceLocations(symbol))

  protected def getWorkspaceLocations(symbol: String): Set[ClassLocation] =
    inheritance.getOrElse(symbol, Set.empty)

  def toGlobal(
      compilers: Compilers,
      implementationsInDependencySources: Map[String, Set[ClassLocation]],
      source: AbsolutePath,
  ) = new GlobalInheritanceContext(
    compilers,
    implementationsInDependencySources,
    inheritance,
    source,
  )
}

class GlobalInheritanceContext(
    compilers: Compilers,
    implementationsInDependencySources: Map[String, Set[ClassLocation]],
    localInheritance: Map[String, Set[ClassLocation]],
    source: AbsolutePath,
) extends InheritanceContext(localInheritance) {
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
        .collect { case loc @ ClassLocation(sym, _) =>
          compilers.info(source, sym).map {
            case Some(symInfo) if symInfo.parents.contains(symbol) => Some(loc)
            case Some(symInfo)
                if symInfo.dealisedSymbol == symbol && symInfo.symbol != symbol =>
              Some(loc)
            case _ => None
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
      localDefinitions: Map[Path, Map[String, Set[ClassLocation]]]
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
    new InheritanceContext(inheritance.toMap)
  }
}
