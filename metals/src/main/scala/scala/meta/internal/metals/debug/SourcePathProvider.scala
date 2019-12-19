package scala.meta.internal.metals.debug

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.debug.Source
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

final class SourcePathProvider(
    definitionProvider: DefinitionProvider,
    buildTargets: BuildTargets,
    targets: List[BuildTargetIdentifier]
) {
  def isWorkspacePath(path: AbsolutePath): Boolean = {
    buildTargets.inverseSources(path).isDefined
  }

  def findPathFor(source: Source): Option[AbsolutePath] = {
    if (source == null) None
    else {
      searchAsClassPathSymbol(source).orElse(searchAsSourceFile(source))
    }
  }

  private def searchAsClassPathSymbol(source: Source): Option[AbsolutePath] = {
    val symbolBase = source.getPath
      .stripSuffix(".scala")
      .stripSuffix(".java")
      .replace("\\", "/") // adapt windows paths to the expected format

    val symbols = for {
      symbol <- Set(symbolBase + ".", symbolBase + "#")
      definition <- definitionProvider.fromSymbol(symbol).asScala
    } yield definition.getUri.toAbsolutePath

    if (symbols.isEmpty) {
      scribe.debug(s"no definition for symbol: $symbolBase")
    }

    symbols.headOption
  }

  private def searchAsSourceFile(source: Source): Option[AbsolutePath] = {
    val files = for {
      target <- targets.view
      sourceFile <- buildTargets.buildTargetSources(target)
      if sourceFile.filename == source.getName
    } yield sourceFile

    if (files.isEmpty) {
      scribe.debug(s"no matching source file: $source")
    }

    files.headOption
  }
}
