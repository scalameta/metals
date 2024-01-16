package scala.meta.internal.implementation
import java.nio.file.Path

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

final class GlobalClassTable(
    buildTargets: BuildTargets
) {
  type ImplementationCache = Map[Path, Map[String, Set[ClassLocation]]]

  private val buildTargetsIndexes =
    TrieMap.empty[BuildTargetIdentifier, GlobalSymbolTable]

  def globalSymbolTableFor(
      source: AbsolutePath
  ): Option[GlobalSymbolTable] =
    synchronized {
      for {
        buildTargetId <- buildTargets.inverseSources(source)
        jarClasspath <- buildTargets.targetJarClasspath(buildTargetId)
        classpath = new Classpath(jarClasspath)
      } yield {
        buildTargetsIndexes.getOrElseUpdate(
          buildTargetId,
          GlobalSymbolTable(classpath, includeJdk = true),
        )
      }
    }

}
