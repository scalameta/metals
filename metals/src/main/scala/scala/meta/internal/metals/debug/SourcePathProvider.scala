package scala.meta.internal.metals.debug

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

final class SourcePathProvider(
    definitionProvider: DefinitionProvider,
    buildTargets: BuildTargets,
    targets: List[BuildTargetIdentifier]
) {
  def findPathFor(
      sourcePath: String,
      sourceName: String
  ): Option[AbsolutePath] = {
    searchAsClassPathSymbol(sourcePath).orElse(searchAsSourceFile(sourceName))
  }

  private def searchAsClassPathSymbol(
      sourcePath: String
  ): Option[AbsolutePath] = {
    val base = sourcePath
      .stripSuffix(".scala")
      .stripSuffix(".java")
      .replace("\\", "/") // adapt windows paths to the expected format

    val symbolBase = if (base.contains("/")) base else "_empty_/" + base
    val symbols = for {
      symbol <- Set(symbolBase + ".", symbolBase + "#").toIterator
      location <- definitionProvider.fromSymbol(symbol, targets).asScala
    } yield location.getUri.toAbsolutePath

    if (symbols.isEmpty) {
      scribe.debug(s"no definition for symbol: $symbolBase")
    }

    symbols.headOption
  }

  private def searchAsSourceFile(sourceName: String): Option[AbsolutePath] = {
    val files = for {
      target <- targets.view
      sourceFile <- buildTargets.buildTargetTransitiveSources(target)
      if sourceFile.filename == sourceName
    } yield sourceFile

    if (files.isEmpty) {
      scribe.debug(s"no matching source file: $sourceName")
    }

    files.headOption
  }
}
