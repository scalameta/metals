package scala.meta.internal.implementation
import scala.meta.internal.metals.BuildTargets
import scala.meta.io.AbsolutePath
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.Classpath
import scala.collection.concurrent.TrieMap
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.TypeSignature
import scala.meta.internal.semanticdb.TypeRef
import scala.collection.mutable

final class GlobalClassTable(
    buildTargets: BuildTargets
) {

  private val buildTargetsIndexes =
    TrieMap.empty[BuildTargetIdentifier, GlobalSymbolTable]

  def indexFor(
      source: AbsolutePath,
      classes: Set[String]
  ): Option[InheritanceContext] = synchronized {
    for {
      buildTargetId <- buildTargets.inverseSources(source)
      index <- calculateIndex(buildTargetId, classes)
    } yield {
      index
    }
  }

  private def calculateIndex(
      buildTargetId: BuildTargetIdentifier,
      classes: Set[String]
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
        classSymbol <- classes
        classInfo <- symTab.info(classSymbol)
      } yield classInfo

      calculateInheritance(symbolsInformation, symTab)
    }
  }

  private def extractType(signature: Signature): Option[String] = {
    signature match {
      case ts: TypeSignature =>
        ts.lowerBound match {
          case tr: TypeRef =>
            Some(tr.symbol)
          case _ => None
        }
      case _ => None
    }
  }

  private def findRealSymbol(
      info: SymbolInformation,
      symtab: GlobalSymbolTable
  ): Option[(Map[String, String], SymbolInformation)] = {
    val mappings = new mutable.ListBuffer[(String, String)]
    var foundBadSymbol = false
    var sym = info
    while (sym.isType && !foundBadSymbol) {
      extractType(sym.signature) match {
        case None => foundBadSymbol = true
        case Some(value) =>
          mappings += sym.symbol -> value
          val newSym = symtab.info(value)
          if (newSym.isDefined) {
            sym = newSym.get
          } else {
            foundBadSymbol = true
          }
      }
    }
    if (!foundBadSymbol) {
      Some((mappings.toMap, sym))
    } else {
      None
    }

  }

  private def calculateInheritance(
      classInfos: Set[SymbolInformation],
      symTab: GlobalSymbolTable
  ): InheritanceContext = {
    val mappings = new mutable.HashMap[String, String]
    val results = new mutable.ListBuffer[(String, ClassLocation)]
    val calculated = mutable.Set.empty[String]
    var infos = classInfos

    while (infos.nonEmpty) {
      calculated ++= infos.map(_.symbol)
      val (typeRefs, allClasses) = infos.partition(_.isType)

      val realSymbols = typeRefs.flatMap { tp =>
        findRealSymbol(tp, symTab)
      }

      val realSymbolsInfo = realSymbols.map(_._2)

      val allParents = (allClasses ++ realSymbolsInfo).flatMap { info =>
        ImplementationProvider.parentsFromSignature(
          info.symbol,
          info.signature,
          None
        )
      }

      mappings ++= realSymbols.map(_._1).flatten
      results ++= allParents
      infos = (allParents.map(_._1) -- calculated).flatMap(symTab.info)
    }

    val inheritance = results.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
    GlobalInheritanceContext(
      symtab = symTab,
      typeMappings = mappings.toMap,
      mappings.map(a => a._2 -> a._1).toMap,
      inheritance = inheritance
    )
  }
}
