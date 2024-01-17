package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.BaseCommand
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.ClientCommands.StartRunSession
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.JavaBinary
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
    workspace: AbsolutePath,
) extends CodeLens {

  override def isEnabled: Boolean =
    clientConfig.isDebuggingProvider() || clientConfig.isRunProvider()

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Seq[l.CodeLens] = {
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath
    val distance = buffers.tokenEditDistance(path, textDocument.text, trees)
    val lenses = for {
      buildTargetId <- buildTargets.inverseSources(path)
      buildTarget <- buildTargets.info(buildTargetId)
      isJVM = buildTarget.asScalaBuildTarget.forall(
        _.getPlatform == b.ScalaPlatform.JVM
      )
      connection <- buildTargets.buildServerOf(buildTargetId)
      // although hasDebug is already available in BSP capabilities
      // see https://github.com/build-server-protocol/build-server-protocol/pull/161
      // most of the bsp servers such as bloop and sbt might not support it.
    } yield {
      val classes = buildTargetClasses.classesOf(buildTargetId)
      // sbt doesn't declare debugging provider
      def buildServerCanDebug =
        connection.isDebuggingProvider || connection.isSbt

      if (connection.isScalaCLI && path.isAmmoniteScript) {
        scalaCliCodeLenses(
          textDocument,
          buildTargetId,
          classes,
          distance,
          buildServerCanDebug,
          isJVM,
        )
      } else if (buildServerCanDebug || clientConfig.isRunProvider()) {
        codeLenses(
          textDocument,
          buildTargetId,
          classes,
          distance,
          path,
          buildServerCanDebug,
          isJVM,
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
      buildServerCanDebug: Boolean,
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
              buildServerCanDebug,
              isJVM = true,
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
      buildServerCanDebug: Boolean,
      isJVM: Boolean,
  ): Seq[l.CodeLens] = {
    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
      symbol = occurrence.symbol
      commands = {
        val main = classes.mainClasses
          .get(symbol)
          .map(mainCommand(target, _, buildServerCanDebug, isJVM))
          .getOrElse(Nil)
        val tests =
          // Currently tests can only be run via DAP
          if (clientConfig.isDebuggingProvider() && buildServerCanDebug)
            testClasses(target, classes, symbol, isJVM)
          else Nil
        val fromAnnot = DebugProvider
          .mainFromAnnotation(occurrence, textDocument)
          .flatMap { symbol =>
            classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _, buildServerCanDebug, isJVM))
          }
          .getOrElse(Nil)
        val javaMains =
          if (path.isJava)
            javaLenses(occurrence, textDocument, target, buildServerCanDebug)
          else Nil
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
      buildServerCanDebug: Boolean,
      isJVM: Boolean,
  ): Seq[l.CodeLens] = {
    val scriptFileName = textDocument.uri.stripSuffix(".sc")

    val expectedMainClass =
      if (scriptFileName.contains('/')) s"${scriptFileName}_sc."
      else s"_empty_/${scriptFileName}_sc."
    val main =
      classes.mainClasses
        .get(expectedMainClass)
        .map(mainCommand(target, _, buildServerCanDebug, isJVM))
        .getOrElse(Nil)

    val fromAnnotations = textDocument.occurrences.flatMap { occ =>
      for {
        sym <- DebugProvider.mainFromAnnotation(occ, textDocument)
        cls <- classes.mainClasses.get(sym)
        range <- occurrenceRange(occ, distance)
      } yield mainCommand(target, cls, buildServerCanDebug, isJVM).map { cmd =>
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
      isJVM: Boolean,
  ): List[l.Command] =
    if (userConfig().testUserInterface == TestUserInterfaceKind.CodeLenses)
      classes.testClasses
        .get(symbol)
        .toList
        .flatMap(symbolInfo =>
          testCommand(target, symbolInfo.fullyQualifiedName, isJVM)
        )
    else
      Nil

  private def testCommand(
      target: b.BuildTargetIdentifier,
      className: String,
      isJVM: Boolean,
  ): List[l.Command] = {
    val params = {
      val dataKind = b.TestParamsDataKind.SCALA_TEST_SUITES
      val data = singletonList(className).toJson
      sessionParams(target, dataKind, data)
    }

    if (isJVM)
      List(
        command("test", StartRunSession, params),
        command("debug test", StartDebugSession, params),
      )
    else
      List(
        command("test", StartRunSession, params)
      )
  }

  private def mainCommand(
      target: b.BuildTargetIdentifier,
      main: b.ScalaMainClass,
      buildServerCanDebug: Boolean,
      isJVM: Boolean,
  ): List[l.Command] = {
    val javaBinary = buildTargets
      .scalaTarget(target)
      .flatMap(scalaTarget =>
        JavaBinary.javaBinaryFromPath(scalaTarget.jvmHome)
      )
      .orElse(userConfig().usedJavaBinary)
    val (data, shellCommandAdded) =
      if (!isJVM) (main.toJson, false)
      else
        buildTargetClasses.jvmRunEnvironment
          .get(target)
          .zip(javaBinary) match {
          case None =>
            (main.toJson, false)
          case Some((env, javaBinary)) =>
            (
              ExtendedScalaMainClass(main, env, javaBinary, workspace).toJson,
              true,
            )
        }
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
      sessionParams(target, dataKind, data)
    }

    if (clientConfig.isDebuggingProvider() && buildServerCanDebug && isJVM)
      List(
        command("run", StartRunSession, params),
        command("debug", StartDebugSession, params),
      )
    // run provider needs shell command to run currently, we don't support pure run inside metals for JVM
    else if (shellCommandAdded && clientConfig.isRunProvider() || !isJVM)
      List(command("run", StartRunSession, params))
    else Nil
  }

  private def sessionParams(
      target: b.BuildTargetIdentifier,
      dataKind: String,
      data: JsonElement,
  ): b.DebugSessionParams = {
    val params = new b.DebugSessionParams(List(target).asJava)
    params.setDataKind(dataKind)
    params.setData(data)
    params
  }

  private def command(
      name: String,
      command: BaseCommand,
      params: b.DebugSessionParams,
  ): l.Command = {
    new l.Command(name, command.id, singletonList(params))
  }
}
