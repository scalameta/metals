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
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

/**
 * Class to generate the Run and Test code lenses to trigger debugging.
 *
 * NOTE: (ckipp01) the isBloopOrSbt param is really only checking to
 * see if the build server connection is bloop or sbt, which support debugging.
 * Despite the fact, that canDebug capability is already a part of the BSP spec,
 * bloop and sbt doesn't support this option, so we have to add additional if
 * in order to generate lenses for them.
 */
final class RunTestCodeLens(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    clientConfig: ClientConfiguration,
    isBloopOrSbt: () => Boolean,
    trees: Trees
) extends CodeLens {

  override def isEnabled: Boolean = clientConfig.isDebuggingProvider()

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath
    if (path.isAmmoniteScript || path.isWorksheet) {
      Seq.empty
    } else {
      val distance = buffers.tokenEditDistance(path, textDocument.text, trees)

      val lenses = for {
        buildTargetId <- buildTargets.inverseSources(path)
        buildTarget <- buildTargets.info(buildTargetId)
        if buildTarget.getCapabilities.getCanDebug || isBloopOrSbt(),
      } yield {
        val classes = buildTargetClasses.classesOf(buildTargetId)
        codeLenses(textDocument, buildTargetId, classes, distance)
      }

      lenses.getOrElse(Seq.empty)
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
      if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
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
        val fromAnnot = mainAnnot(occurrence, textDocument)
          .flatMap { symbol =>
            classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _))
          }
          .getOrElse(Nil)
        main ++ tests ++ fromAnnot
      }
      if commands.nonEmpty
      range <-
        occurrence.range
          .flatMap(r => distance.toRevisedStrict(r).map(_.toLSP))
          .toList
      command <- commands
    } yield new l.CodeLens(range, command, null)
  }

  private def mainAnnot(
      occurrence: SymbolOccurrence,
      textDocument: TextDocument
  ): Option[String] = {
    if (occurrence.symbol == "scala/main#") {
      occurrence.range match {
        case Some(range) =>
          val closestOccurence = textDocument.occurrences.minBy { occ =>
            occ.range
              .filter { rng =>
                occ.symbol != "scala/main#" &&
                rng.endLine - range.endLine >= 0 &&
                rng.endCharacter - rng.startCharacter > 0
              }
              .map(rng =>
                (
                  rng.endLine - range.endLine,
                  rng.endCharacter - range.endCharacter
                )
              )
              .getOrElse((Int.MaxValue, Int.MaxValue))
          }
          dropSourceFromToplevelSymbol(closestOccurence.symbol)

        case None => None
      }
    } else {
      None
    }

  }

  /**
   * Converts Scala3 sorceToplevelSymbol into a plain one that corresponds to class name.
   * From `3.1.0` plain names were removed from occurrences because they are synthetic.
   * Example:
   *   `foo/Foo$package.mainMethod().` -> `foo/mainMethod#`
   */
  private def dropSourceFromToplevelSymbol(symbol: String): Option[String] = {
    Symbol(symbol) match {
      case GlobalSymbol(
            GlobalSymbol(
              owner,
              Descriptor.Term(sourceOwner)
            ),
            Descriptor.Method(name, _)
          ) if sourceOwner.endsWith("$package") =>
        val converted = GlobalSymbol(owner, Descriptor.Term(name))
        Some(converted.value)
      case _ =>
        None
    }
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
