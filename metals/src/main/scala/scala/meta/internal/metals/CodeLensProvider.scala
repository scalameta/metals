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

final class CodeLensProvider(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    compilations: Compilations,
    semanticdbs: Semanticdbs
)(implicit ec: ExecutionContext) {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    val lenses = buildTargets
      .inverseSources(path)
      .filterNot(compilations.isCurrentlyCompiling)
      .map { buildTarget =>
        val classes = buildTargetClasses.classesOf(buildTarget)
        val lenses = findLenses(path, buildTarget, classes)
        lenses
      }

    lenses.getOrElse(Nil)
  }

  private def findLenses(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes
  ): Seq[l.CodeLens] = {
    semanticdbs.textDocument(path).documentIncludingStale match {
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
              .map(RunCommandFactory.command(target, _))
              .toList
            val tests = classes.testSuites
              .get(symbol)
              .map(TestCommandFactory.command(target, _))
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
  import JsonParser._

  val RunCommandFactory =
    new CommandFactory[b.ScalaMainClass](
      "run",
      b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
      mainClass => mainClass.toJson
    )

  val TestCommandFactory =
    new CommandFactory[String](
      "test",
      b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
      suite => singletonList(suite).toJson
    )

  final class CommandFactory[A](
      name: String,
      dataKind: String,
      serialize: A => JsonElement
  ) {
    def command(target: b.BuildTargetIdentifier, data: A): l.Command = {
      val params = new b.DebugSessionParams(
        List(target).asJava,
        dataKind,
        serialize(data)
      )

      new l.Command(name, StartDebugSession.id, singletonList(params))
    }
  }
}
