package scala.meta.internal.metals

import java.util.Collections._

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

import scala.concurrent.ExecutionContext
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.SuperMethodProvider
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.ClientCommands.StartRunSession
import scala.meta.internal.metals.CodeLensProvider._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.io.AbsolutePath

trait CodeLensProvider {
  def findLenses(path: AbsolutePath): Seq[l.CodeLens]
}

final class DebugCodeLensProvider(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    config: MetalsServerConfig,
    semanticdbs: Semanticdbs,
    superMethodProvider: SuperMethodProvider
)(implicit ec: ExecutionContext)
    extends CodeLensProvider {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    val lenses = buildTargets
      .inverseSources(path)
      .map { buildTarget =>
        val classes = buildTargetClasses.classesOf(buildTarget)
        codeLenses(path, buildTarget, classes)
      }

    lenses.getOrElse(Nil)
  }

  private def codeLenses(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes
  ): Seq[l.CodeLens] = {
    semanticdbs.textDocument(path).documentIncludingStale match {
      case None =>
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
              .getOrElse(Nil)
            val tests = classes.testClasses
              .get(symbol)
              .map(testCommand(target, _))
              .getOrElse(Nil)
            val docWithPath = TextDocumentWithPath(textDocument, path)
            val gotoSuperMethod =
              createSuperMethodCommand(docWithPath, symbol, occurrence.role)
            main ++ tests ++ gotoSuperMethod
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

  private def createSuperMethodCommand(
      docWithPath: TextDocumentWithPath,
      symbol: String,
      role: SymbolOccurrence.Role
  ): Option[l.Command] = {
    for {
      symbolInformation <- ImplementationProvider.findSymbol(
        docWithPath.textDocument,
        symbol
      )
      gotoLocation <- superMethodProvider.findSuperForMethodOrField(
        symbolInformation,
        docWithPath,
        role
      )
    } yield convertToSuperMethodCommand(
      gotoLocation,
      symbolInformation.displayName
    )
  }

  private def convertToSuperMethodCommand(
      gotoLocation: l.Location,
      name: String
  ): l.Command = {
    new l.Command(
      s"${config.icons.findsuper} ${name}",
      ClientCommands.GotoLocation.id,
      singletonList(gotoLocation)
    )
  }
}

object CodeLensProvider {
  import scala.meta.internal.metals.JsonParser._

  private val Empty: CodeLensProvider = (_: AbsolutePath) => Nil

  def apply(
      buildTargetClasses: BuildTargetClasses,
      buffers: Buffers,
      buildTargets: BuildTargets,
      semanticdbs: Semanticdbs,
      config: MetalsServerConfig,
      superMethodProvider: SuperMethodProvider,
      capabilities: ClientExperimentalCapabilities
  )(implicit ec: ExecutionContext): CodeLensProvider = {
    if (!capabilities.debuggingProvider) Empty
    else {
      new DebugCodeLensProvider(
        buildTargetClasses,
        buffers,
        buildTargets,
        config,
        semanticdbs,
        superMethodProvider
      )
    }
  }

  def testCommand(
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

  def mainCommand(
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
      command: Command,
      params: b.DebugSessionParams
  ): l.Command = {
    new l.Command(name, command.id, singletonList(params))
  }
}
