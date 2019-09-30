package scala.meta.internal.implementation
import scala.meta.internal.metals.BuildTargets
import scala.meta.io.AbsolutePath
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.Classpath
import scala.collection.concurrent.TrieMap
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.internal.semanticdb.SymbolInformation
import scala.collection.mutable

final class GlobalClassTable(
    buildTargets: BuildTargets
) {

  private val buildTargetsIndexes =
    TrieMap.empty[BuildTargetIdentifier, GlobalSymbolTable]

  // TODO test if we should also forward aliases here
  def indexFor(
      source: AbsolutePath,
      context: InheritanceContext
  ): Option[InheritanceContext] = synchronized {
    for {
      buildTargetId <- buildTargets.inverseSources(source)
      index <- calculateIndex(buildTargetId, context)
    } yield {
      index
    }
  }

  private def calculateIndex(
      buildTargetId: BuildTargetIdentifier,
      context: InheritanceContext
  ): Option[InheritanceContext] = {
    for {
      scalaTarget <- buildTargets.scalaTarget(buildTargetId)
      classpath = new Classpath(scalaTarget.classpath)
      symTab = buildTargetsIndexes.getOrElseUpdate(
        buildTargetId,
        GlobalSymbolTable(classpath, includeJdk = true)
      )
    } yield {
      val symbolsInformation = for {
        classSymbol <- context.allClassSymbols
        classInfo <- symTab.info(classSymbol)
      } yield classInfo

      calculateInheritance(symbolsInformation, context, symTab)
    }
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
      infos = (allParents.map(_._1) -- calculated).flatMap(symTab.info)
    }

    val inheritance = results.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
    context.withClasspathContext(inheritance, symTab.info(_))
  }

}
