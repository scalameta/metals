package scala.meta.internal.metals

import java.util.Collections._
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.CodeLensProvider._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath

trait CodeLensProvider {
  def findLenses(path: AbsolutePath): Seq[l.CodeLens]
}

final class DebugCodeLensProvider(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    compilations: Compilations,
    semanticdbs: Semanticdbs
)(implicit ec: ExecutionContext)
    extends CodeLensProvider {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    val lenses = buildTargets
      .inverseSources(path)
      .map { buildTarget =>
        val classes = buildTargetClasses.classesOf(buildTarget)
        val lenses = codeLenses(path, buildTarget, classes)
        scribe.info(s"Lenses for $path: $lenses")
        lenses
      }

    lenses.getOrElse(Nil)
  }

  private def codeLenses(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes
  ): Seq[l.CodeLens] = {
    semanticdbs.textDocument(path).documentIncludingStale match {
      case _ if classes.isEmpty =>
        Nil
      case Some(textDocument) =>
        val distance =
          TokenEditDistance.fromBuffer(path, textDocument.text, buffers)

        for {
          occurrence <- textDocument.occurrences
          if occurrence.role.isDefinition
          symbol = occurrence.symbol
          commands = {
            val main = classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _))
              .toList
            val tests = classes.testClasses
              .get(symbol)
              .map(testCommand(target, _))
              .toList
            main ++ tests
          }
          if commands.nonEmpty
          range <- occurrence.range
            .flatMap(r => distance.toRevised(r.toLSP))
            .toList
          command <- commands
        } yield new l.CodeLens(range, command, null)
      case _ =>
        Nil
    }
  }
}

object CodeLensProvider {
  import scala.meta.internal.metals.JsonParser._

  private val Empty: CodeLensProvider = (_: AbsolutePath) => Nil

  def apply(
      buildTargetClasses: BuildTargetClasses,
      buffers: Buffers,
      buildTargets: BuildTargets,
      compilations: Compilations,
      semanticdbs: Semanticdbs,
      capabilities: ClientExperimentalCapabilities
  )(implicit ec: ExecutionContext): CodeLensProvider = {
    if (!capabilities.debuggingProvider) Empty
    else {
      new DebugCodeLensProvider(
        buildTargetClasses,
        buffers,
        buildTargets,
        compilations,
        semanticdbs
      )
    }
  }

  def testCommand(
      target: b.BuildTargetIdentifier,
      className: String
  ): l.Command = {
    val name = "test"
    val dataKind = b.DebugSessionParamsDataKind.SCALA_TEST_SUITES
    val data = singletonList(className).toJson

    command(target, name, dataKind, data)
  }

  def mainCommand(
      target: b.BuildTargetIdentifier,
      main: b.ScalaMainClass
  ): l.Command = {
    val name = "run"
    val dataKind = b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
    val data = main.toJson

    command(target, name, dataKind, data)
  }

  private def command(
      target: b.BuildTargetIdentifier,
      name: String,
      dataKind: String,
      data: JsonElement
  ): l.Command = {
    val params = new b.DebugSessionParams(List(target).asJava, dataKind, data)
    new l.Command(name, StartDebugSession.id, singletonList(params))
  }
}
