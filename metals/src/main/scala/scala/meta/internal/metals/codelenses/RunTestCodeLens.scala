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
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.debug.ExtendedScalaMainClass
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.io.AbsolutePath

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
    userConfig: () => UserConfiguration,
    trees: Trees,
) extends CodeLens {

  override def isEnabled: Boolean = clientConfig.isDebuggingProvider()

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath
    val distance = buffers.tokenEditDistance(path, textDocument.text, trees)
    val lenses = for {
      buildTargetId <- buildTargets.inverseSources(path)
      buildTarget <- buildTargets.info(buildTargetId)
      connection <- buildTargets.buildServerOf(buildTargetId)
      // although hasDebug is already available in BSP capabilities
      // see https://github.com/build-server-protocol/build-server-protocol/pull/161
      // most of the bsp servers such as bloop and sbt might not support it.
    } yield {
      val classes = buildTargetClasses.classesOf(buildTargetId)
      if (connection.isScalaCLI && path.isAmmoniteScript) {
        scalaCliCodeLenses(textDocument, buildTargetId, classes, distance)
      } else if (
        buildTarget.getCapabilities.getCanDebug || connection.isBloop || connection.isSbt
      ) {
        codeLenses(
          textDocument,
          buildTargetId,
          classes,
          distance,
          path,
        )
      } else { Nil }

    }

    lenses.getOrElse(Seq.empty)
  }

  /**
   * Java main method: public static void main(String[] args)
   */
  private def isMainMethod(signature: Signature, textDocument: TextDocument) = {
    signature match {
      case MethodSignature(_, Seq(Scope(Seq(param), _)), returnType) =>
        def isVoid = returnType match {
          case TypeRef(_, symbol, _) => symbol == "scala/Unit#"
          case _ => false
        }

        def isStringArray(sym: String) = {
          textDocument.symbols.find(
            _.symbol == sym
          ) match {
            case Some(info) =>
              info.signature match {
                case ValueSignature(
                      TypeRef(
                        _,
                        "scala/Array#",
                        Vector(TypeRef(_, "java/lang/String#", _)),
                      )
                    ) =>
                  true
                case _ => false
              }
            case None => false
          }
        }

        isVoid && isStringArray(param)
      case _ => false
    }
  }

  private def javaLenses(
      occurence: SymbolOccurrence,
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
  ): Seq[l.Command] = {
    if (occurence.symbol.endsWith("#main().")) {
      textDocument.symbols
        .find(_.symbol == occurence.symbol)
        .toSeq
        .flatMap { a =>
          val isMain =
            a.isPublic && a.isStatic && isMainMethod(a.signature, textDocument)
          if (isMain)
            mainCommand(
              target,
              new b.ScalaMainClass(
                occurence.symbol.stripSuffix("#main().").replace("/", "."),
                Nil.asJava,
                Nil.asJava,
              ),
            )
          else
            Nil
        }
    } else {
      Nil
    }

  }

  private def codeLenses(
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      distance: TokenEditDistance,
      path: AbsolutePath,
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
        val tests = testClasses(target, classes, symbol)
        val fromAnnot = DebugProvider
          .mainFromAnnotation(occurrence, textDocument)
          .flatMap { symbol =>
            classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _))
          }
          .getOrElse(Nil)
        val javaMains =
          if (path.isJava) javaLenses(occurrence, textDocument, target) else Nil
        main ++ tests ++ fromAnnot ++ javaMains
      }
      if commands.nonEmpty
      range <- occurrenceRange(occurrence, distance).toList
      command <- commands
    } yield new l.CodeLens(range, command, null)
  }

  private def occurrenceRange(
      occurrence: SymbolOccurrence,
      distance: TokenEditDistance,
  ): Option[l.Range] =
    occurrence.range
      .flatMap(r => distance.toRevisedStrict(r).map(_.toLsp))

  private def scalaCliCodeLenses(
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      distance: TokenEditDistance,
  ): Seq[l.CodeLens] = {
    val scriptFileName = textDocument.uri.stripSuffix(".sc")

    val expectedMainClass =
      if (scriptFileName.contains('/')) s"${scriptFileName}_sc."
      else s"_empty_/${scriptFileName}_sc."
    val main =
      classes.mainClasses
        .get(expectedMainClass)
        .map(mainCommand(target, _))
        .getOrElse(Nil)

    val fromAnnotations = textDocument.occurrences.flatMap { occ =>
      for {
        sym <- DebugProvider.mainFromAnnotation(occ, textDocument)
        cls <- classes.mainClasses.get(sym)
        range <- occurrenceRange(occ, distance)
      } yield mainCommand(target, cls).map { cmd =>
        new l.CodeLens(range, cmd, null)
      }
    }.flatten
    fromAnnotations ++ main.map(command =>
      new l.CodeLens(
        new l.Range(new l.Position(0, 0), new l.Position(0, 2)),
        command,
        null,
      )
    )
  }

  /**
   * Do not return test code lenses if user declared test explorer as a test interface.
   */
  private def testClasses(
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      symbol: String,
  ): List[l.Command] =
    if (userConfig().testUserInterface == TestUserInterfaceKind.CodeLenses)
      classes.testClasses
        .get(symbol)
        .toList
        .flatMap(symbolInfo =>
          testCommand(target, symbolInfo.fullyQualifiedName)
        )
    else
      Nil

  private def testCommand(
      target: b.BuildTargetIdentifier,
      className: String,
  ): List[l.Command] = {
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_TEST_SUITES
      val data = singletonList(className).toJson
      sessionParams(target, dataKind, data)
    }

    List(
      command("test", StartRunSession, params),
      command("debug test", StartDebugSession, params),
    )
  }

  private def mainCommand(
      target: b.BuildTargetIdentifier,
      main: b.ScalaMainClass,
  ): List[l.Command] = {
    val data = buildTargets
      .jvmRunEnvironment(target)
      .zip(userConfig().usedJavaBinary) match {
      case None =>
        main.toJson
      case Some((env, javaHome)) =>
        ExtendedScalaMainClass(main, env, javaHome).toJson
    }
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
      sessionParams(target, dataKind, data)
    }

    List(
      command("run", StartRunSession, params),
      command("debug", StartDebugSession, params),
    )
  }

  private def sessionParams(
      target: b.BuildTargetIdentifier,
      dataKind: String,
      data: JsonElement,
  ): b.DebugSessionParams = {
    new b.DebugSessionParams(List(target).asJava, dataKind, data)
  }

  private def command(
      name: String,
      command: BaseCommand,
      params: b.DebugSessionParams,
  ): l.Command = {
    new l.Command(name, command.id, singletonList(params))
  }
}
