package scala.meta.internal.metals.codelenses

import java.util.Collections.singletonList

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.BaseCommand
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.ClientCommands.StartRunSession
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.DebugDiscovery
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
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
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
    diagnostics: Diagnostics,
)(implicit val ec: ExecutionContext)
    extends CodeLens {

  override def isEnabled: Boolean =
    clientConfig.isDebuggingProvider() || clientConfig.isRunProvider()

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Future[Seq[l.CodeLens]] = {

    /**
     * Jvm environment needs to be requested lazily.
     * This requests and caches it for later use, otherwise we
     * would need to forward Future across different methods
     * which would make things way too complex.
     */
    def requestJvmEnvironment(
        buildTargetId: BuildTargetIdentifier,
        isJvm: Boolean,
    ): Future[Unit] = {
      if (isJvm)
        buildTargetClasses.jvmRunEnvironment(buildTargetId).map(_ => ())
      else
        Future.unit
    }
    val textDocument = textDocumentWithPath.textDocument
    val path = textDocumentWithPath.filePath
    val distance = buffers.tokenEditDistance(path, textDocument.text, trees)
    val lenses = for {
      buildTargetId <- buildTargets.inverseSources(path)
      if canActuallyCompile(buildTargetId, diagnostics)
      buildTarget <- buildTargets.info(buildTargetId)
      isJVM = buildTarget.asScalaBuildTarget.forall(
        _.getPlatform == b.ScalaPlatform.JVM
      )
      connection <- buildTargets.buildServerOf(buildTargetId)
      // although hasDebug is already available in BSP capabilities
      // see https://github.com/build-server-protocol/build-server-protocol/pull/161
      // most of the bsp servers such as bloop and sbt might not support it.
    } yield requestJvmEnvironment(buildTargetId, isJVM).map { _ =>
      val classes = buildTargetClasses.classesOf(buildTargetId)
      val syntheticLenses = syntheticCodeLenses(
        textDocument,
        buildTargetId,
        classes,
        isJVM,
      )
      val regularLenses =
        codeLenses(
          textDocument,
          buildTargetId,
          classes,
          distance,
          path,
          isJVM,
        )
      syntheticLenses ++ regularLenses
    }
    lenses.getOrElse(Future.successful(Nil))
  }

  /**
   * Checks if there exist any files outside of the META-INF directory of build target classpath.
   *
   * When using Scala 3 Best Effort compilation, the compilation may fail,
   * but semanticDB and betasty (and thus the META_INF directory) may still be produced.
   * We want to avoid creating run/test/debug code lens in those situations.
   */
  private def canActuallyCompile(
      buildTargetId: BuildTargetIdentifier,
      diagnostics: Diagnostics,
  ): Boolean = {
    val allBuildTargetsIds: Seq[BuildTargetIdentifier] =
      buildTargetId +: (buildTargets
        .buildTargetTransitiveDependencies(buildTargetId)
        .toSeq)
    allBuildTargetsIds.forall { id =>
      !diagnostics.hasCompilationErrors(id)
    }
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
              isJVM = true,
            )
          else
            Nil
        }
    } else {
      Nil
    }

  }

  private def syntheticCodeLenses(
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      isJVM: Boolean,
  ): Seq[l.CodeLens] = {
    val symbolsWithMain = textDocument.symbols.filter(info =>
      classes.mainClasses.contains(info.symbol)
    )
    for {
      sym <- symbolsWithMain
      if ! {
        textDocument.occurrences.exists(occ =>
          occ.symbol == sym.symbol && occ.role.isDefinition
        )
      }
      command <- classes.mainClasses
        .get(sym.symbol)
        .map(
          mainCommand(target, _, isJVM, adjustName = s" (${sym.displayName})")
        )
        .getOrElse(Nil)
    } yield new l.CodeLens(
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      command,
      null,
    )

  }

  private def codeLenses(
      textDocument: TextDocument,
      target: BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes,
      distance: TokenEditDistance,
      path: AbsolutePath,
      isJVM: Boolean,
  ): Seq[l.CodeLens] = {
    for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
      symbol = occurrence.symbol
      commands = {
        val main = classes.mainClasses
          .get(symbol)
          .map(mainCommand(target, _, isJVM))
          .getOrElse(Nil)
        lazy val tests =
          // Currently tests can only be run via DAP
          if (clientConfig.isDebuggingProvider())
            testClasses(target, classes, symbol, isJVM)
          else Nil
        val fromAnnot = DebugDiscovery
          .mainFromAnnotation(occurrence, textDocument)
          .flatMap { symbol =>
            classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _, isJVM))
          }
          .getOrElse(Nil)
        val javaMains =
          if (path.isJava)
            javaLenses(occurrence, textDocument, target)
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
      isJVM: Boolean,
      adjustName: String = "",
  ): List[l.Command] = {
    val javaBinary = buildTargets
      .scalaTarget(target)
      .flatMap(scalaTarget =>
        JavaBinary.javaBinaryFromPath(scalaTarget.jvmHome)
      )
      .orElse(userConfig().usedJavaBinary())
    val (data, shellCommandAdded) =
      if (!isJVM) (main.toJson, false)
      else
        buildTargetClasses
          .jvmRunEnvironmentSync(target)
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

    if (clientConfig.isDebuggingProvider() && isJVM)
      List(
        command("run" + adjustName, StartRunSession, params),
        command("debug" + adjustName, StartDebugSession, params),
      )
    // run provider needs shell command to run currently, we don't support pure run inside metals for JVM
    else if (shellCommandAdded && clientConfig.isRunProvider() || !isJVM)
      List(command("run" + adjustName, StartRunSession, params))
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
