package scala.meta.internal.implementation
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

final class GlobalClassTable(
    buildTargets: BuildTargets
) {

  import ImplementationProvider._

  type ImplementationCache = Map[Path, Map[String, Set[ClassLocation]]]

  private val buildTargetsIndexes =
    TrieMap.empty[BuildTargetIdentifier, GlobalSymbolTable]

  def globalContextFor(
      source: AbsolutePath,
      implementationsInPath: ImplementationCache
  ): Option[InheritanceContext] = {
    for {
      symtab <- globalSymbolTableFor(source)
    } yield {
      calculateIndex(symtab, implementationsInPath)
    }
  }

  def globalSymbolTableFor(
      source: AbsolutePath
  ): Option[GlobalSymbolTable] =
    synchronized {
      for {
        buildTargetId <- buildTargets.inverseSources(source)
        scalaTarget <- buildTargets.scalaTarget(buildTargetId)
        classpath = new Classpath(scalaTarget.jarClasspath)
      } yield {
        buildTargetsIndexes.getOrElseUpdate(
          buildTargetId,
          GlobalSymbolTable(classpath, includeJdk = true)
        )
      }
    }

  private def calculateIndex(
      symTab: GlobalSymbolTable,
      implementationsInPath: ImplementationCache
  ): InheritanceContext = {
    val context = InheritanceContext.fromDefinitions(
      symTab.safeInfo,
      implementationsInPath.toMap
    )
    val symbolsInformation = for {
      classSymbol <- context.allClassSymbols
      classInfo <- symTab.safeInfo(classSymbol)
    } yield classInfo

    calculateInheritance(symbolsInformation, context, symTab)

  }

  private def calculateInheritance(
      classpathClassInfos: Set[SymbolInformation],
      context: InheritanceContext,
      symTab: GlobalSymbolTable
  ): InheritanceContext = {
    val results = new mutable.ListBuffer[(String, ClassLocation)]
    val calculated = mutable.Set.empty[String]
    var infos = classpathClassInfos

    while (infos.nonEmpty) {
      calculated ++= infos.map(_.symbol)

      val allParents = infos.flatMap { info =>
        ImplementationProvider.parentsFromSignature(
          info.symbol,
          info.signature,
          None
        )
      }
      results ++= allParents
      infos = (allParents.map(_._1) -- calculated).flatMap(symTab.safeInfo)
    }

    val inheritance = results.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
    context.withClasspathContext(inheritance)
  }

}
