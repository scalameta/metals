package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.BaseCommand
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.ClientCommands.StartRunSession
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

/**
 * Class to generate the Run and Test code lenses to trigger debugging.
 *
 * NOTE: (ckipp01) the buildServerCanDebug param is really only checking to
 * see if the build server connection is bloop, which supports DAP. In the
 * case of sbt, it doesn't so we don't want to generate these.  In the future,
 * if this becomes part of the BSP spec, we should be able to somply check to
 * see if the build server is a DAP provider, and then generate these based
 * off that.
 */
final class RunTestCodeLens(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    clientConfig: ClientConfiguration,
    buildServerCanDebug: () => Boolean,
    trees: Trees
) extends CodeLens {

  override def isEnabled: Boolean =
    clientConfig.isDebuggingProvider && buildServerCanDebug()

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath
    if (path.isAmmoniteScript || path.isWorksheet) {
      Seq.empty
    } else {
      val distance = buffers.tokenEditDistance(path, textDocument.text, trees)

      buildTargets
        .inverseSources(path)
        .map { buildTarget =>
          val classes = buildTargetClasses.classesOf(buildTarget)
          codeLenses(textDocument, buildTarget, classes, distance)
        }
        .getOrElse(Seq.empty)

    }
  }

  private def codeLenses(
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      distance: TokenEditDistance
  ): Seq[l.CodeLens] = {
    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition
      symbol = occurrence.symbol
      commands = {
        val main = classes.mainClasses
          .get(symbol)
          .map(mainCommand(target, _))
          .getOrElse(Nil)
        val tests = classes.testClasses
          .get(symbol)
          .map(testCommand(target, _))
          .getOrElse(Nil)
        main ++ tests
      }
      if commands.nonEmpty
      range <-
        occurrence.range
          .flatMap(r => distance.toRevised(r.toLSP))
          .toList
      command <- commands
    } yield new l.CodeLens(range, command, null)
  }

  private def testCommand(
      target: b.BuildTargetIdentifier,
      className: String
  ): List[l.Command] = {
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_TEST_SUITES
      val data = singletonList(className).toJson
      sessionParams(target, dataKind, data)
    }

    List(
      command("test", StartRunSession, params),
      command("debug test", StartDebugSession, params)
    )
  }

  private def mainCommand(
      target: b.BuildTargetIdentifier,
      main: b.ScalaMainClass
  ): List[l.Command] = {
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
      val data = main.toJson
      sessionParams(target, dataKind, data)
    }

    List(
      command("run", StartRunSession, params),
      command("debug", StartDebugSession, params)
    )
  }

  private def sessionParams(
      target: b.BuildTargetIdentifier,
      dataKind: String,
      data: JsonElement
  ): b.DebugSessionParams = {
    new b.DebugSessionParams(List(target).asJava, dataKind, data)
  }

  private def command(
      name: String,
      command: BaseCommand,
      params: b.DebugSessionParams
  ): l.Command = {
    new l.Command(name, command.id, singletonList(params))
  }
}
