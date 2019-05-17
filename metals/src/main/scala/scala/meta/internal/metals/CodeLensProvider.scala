package scala.meta.internal.metals
import java.util
import java.util.Collections._
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath

final class CodeLensProvider(
    classes: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    semanticdbs: Semanticdbs
) {
  def findLenses(path: AbsolutePath): util.List[l.CodeLens] = {
    buildTargets.inverseSources(path) match {
      case Some(buildTarget) if classes.main(buildTarget).nonEmpty =>
        findLenses(path, buildTarget).asJava
      case _ =>
        emptyList[l.CodeLens]()
    }
  }

  private def findLenses(
      path: AbsolutePath,
      buildTarget: BuildTargetIdentifier
  ): List[l.CodeLens] = {
    semanticdbs.textDocument(path).documentIncludingStale match {
      case Some(textDocument) =>
        val distance =
          TokenEditDistance.fromBuffer(path, textDocument.text, buffers)
        val mainClasses = classes.main(buildTarget)

        val lenses = for {
          occurrence <- textDocument.occurrences
          if mainClasses.contains(occurrence.symbol)
          mainClass = mainClasses(occurrence.symbol)
          range <- occurrence.range
            .flatMap(r => distance.toRevised(r.toLSP))
            .toList
          arguments = List(buildTarget.getUri, mainClass.getClassName)
        } yield
          new l.CodeLens(range, ClientCommands.RunCode.toLSP(arguments), null)
        lenses.toList
      case _ =>
        Nil
    }
  }
}
