package scala.meta.internal.metals.debug

import java.util.Collections.singletonList

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.config.RunType._
import scala.meta.internal.metals.debug.DebugProvider.NoRunOptionException
import scala.meta.internal.metals.debug.DebugProvider.SemanticDbNotFoundException
import scala.meta.internal.metals.debug.DebugProvider.UndefinedPathException
import scala.meta.internal.metals.debug.DebugProvider.WorkspaceErrorsException
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument

class DebugDiscovery(
    buildTargetClasses: BuildTargetClasses,
    buildTargets: BuildTargets,
    buildClient: MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    semanticdbs: () => Semanticdbs,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
) {

  /**
   * Tries to discover the main class to run and returns
   * DebugSessionParams that contains the shellCommand field.
   * This is used so that clients can easily run the full command
   * if they want.
   */
  def runCommandDiscovery(
      unresolvedParams: DebugDiscoveryParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    debugDiscovery(unresolvedParams).flatMap(enrichWithMainShellCommand)
  }

  /**
   * Given fully unresolved params this figures out the runType that was passed
   * in and then discovers either the main methods for the build target the
   * path belongs to or finds the tests for the current file or build target
   */
  def debugDiscovery(
      params: DebugDiscoveryParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val runTypeO = RunType.fromString(params.runType)
    val pathOpt = Option(params.path).map(_.toAbsolutePath)
    val buildTargetO = pathOpt.flatMap(buildTargets.inverseSources(_)).orElse {
      Option(params.buildTarget)
        .flatMap(buildTargets.findByDisplayName)
        .map(_.getId())
    }

    lazy val mainClasses = (bti: b.BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).mainClasses

    lazy val testClasses = (bti: b.BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).testClasses

    (runTypeO, buildTargetO, pathOpt) match {
      case (_, Some(buildTarget), _)
          if buildClient.buildHasErrors(buildTarget) =>
        Future.failed(WorkspaceErrorsException)
      case (_, None, Some(path)) =>
        Future.failed(BuildTargetNotFoundForPathException(path))
      case (None, _, _) =>
        Future.failed(RunType.UnknownRunTypeException(params.runType))
      case (Some(Run), target, _) =>
        val targetIds = target match {
          case None => buildTargets.allBuildTargetIds
          case Some(value) => List(value)
        }
        val requestedMain = Option(params.mainClass)
        val targetToMainClasses = targetIds
          .map { target =>
            target ->
              mainClasses(target).values.toList.filter(cls =>
                requestedMain.isEmpty || requestedMain.contains(
                  cls.getClassName()
                )
              )

          }
          .filter { case (_, mains) => mains.nonEmpty }
          .toMap
        if (targetToMainClasses.nonEmpty)
          verifyMain(
            targetToMainClasses,
            params,
          )
        else
          Future.failed(
            MainClassException(
              params,
              targetIds.nonEmpty,
              buildTargets.all.map(_.getDisplayName).toSeq,
            )
          )
      case (Some(RunOrTestFile), Some(target), _) =>
        resolveInFile(
          target,
          mainClasses(target),
          testClasses(target),
          params,
        )
      case (Some(TestFile), Some(target), path)
          if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("file", path.toString())
        )
      case (Some(TestTarget), Some(target), _) if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("build target", displayName(target))
        )
      case (Some(TestTarget), None, _) =>
        Future.failed(NoBuildTargetSpecified)
      case (Some(TestFile), Some(target), Some(path)) =>
        semanticdbs()
          .textDocument(path)
          .documentIncludingStale
          .fold[Future[Seq[BuildTargetClasses.FullyQualifiedClassName]]] {
            Future.failed(SemanticDbNotFoundException)
          } { textDocument =>
            Future {
              for {
                symbolInfo <- textDocument.symbols
                symbol = symbolInfo.symbol
                testSymbolInfo <- testClasses(target).get(symbol)
              } yield testSymbolInfo.fullyQualifiedName
            }
          }
          .map { tests =>
            val params = new b.DebugSessionParams(
              singletonList(target)
            )
            params.setDataKind(
              b.TestParamsDataKind.SCALA_TEST_SUITES
            )
            params.setData(tests.asJava.toJson)
            params
          }
      case (Some(TestTarget), Some(target), _) =>
        Future {
          val params = new b.DebugSessionParams(
            singletonList(target)
          )
          params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
          params.setData(
            testClasses(target).values
              .map(_.fullyQualifiedName)
              .toList
              .asJava
              .toJson
          )
          params
        }
      case (Some(tpe @ (TestFile | RunOrTestFile)), _, None) =>
        Future.failed(UndefinedPathException(tpe))

    }

  }

  private def verifyMain(
      classes: Map[b.BuildTargetIdentifier, List[b.ScalaMainClass]],
      params: DebugDiscoveryParams,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {

    classes.toList match {
      case (buildTarget, main :: Nil) :: Nil =>
        createMainParams(
          params,
          main,
          buildTarget,
        )
      case multiple =>
        requestMain(multiple.flatMap(_._2)).flatMap { main =>
          val buildTarget = classes.find(_._2.contains(main)).map(_._1)
          createMainParams(
            params,
            main,
            buildTarget.get,
          )
        }

    }
  }

  private def enrichWithMainShellCommand(
      params: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val future = params.getData() match {
      case json: JsonElement
          if params.getDataKind == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json.as[b.ScalaMainClass] match {
          case Success(main) if params.getTargets().size > 0 =>
            val javaBinary = buildTargets
              .scalaTarget(params.getTargets().get(0))
              .flatMap(scalaTarget =>
                JavaBinary.javaBinaryFromPath(scalaTarget.jvmHome)
              )
              .orElse(userConfig().usedJavaBinary)
            buildTargetClasses
              .jvmRunEnvironment(params.getTargets().get(0))
              .map { envItem =>
                val updatedData = envItem.zip(javaBinary) match {
                  case None =>
                    main.toJson
                  case Some((env, javaHome)) =>
                    ExtendedScalaMainClass(
                      main,
                      env,
                      javaHome,
                      workspace,
                    ).toJson
                }
                params.setData(updatedData)
              }
          case _ => Future.unit
        }

      case _ => Future.unit
    }

    future.map { _ =>
      params
    }
  }

  private def resolveInFile(
      buildTarget: b.BuildTargetIdentifier,
      classes: TrieMap[
        BuildTargetClasses.Symbol,
        b.ScalaMainClass,
      ],
      testClasses: TrieMap[
        BuildTargetClasses.Symbol,
        BuildTargetClasses.TestSymbolInfo,
      ],
      params: DebugDiscoveryParams,
  )(implicit ec: ExecutionContext) = {
    val path = params.path.toAbsolutePath
    semanticdbs()
      .textDocument(path)
      .documentIncludingStale
      .fold[Future[b.DebugSessionParams]] {
        Future.failed(SemanticDbNotFoundException)
      } { textDocument =>
        lazy val tests = for {
          symbolInfo <- textDocument.symbols
          symbol = symbolInfo.symbol
          testSymbolInfo <- testClasses.get(symbol)
        } yield testSymbolInfo.fullyQualifiedName
        val mains = for {
          occurrence <- textDocument.occurrences
          if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
          symbol = occurrence.symbol
          mainClass <- {
            val normal = classes.get(symbol)
            val fromAnnot = DebugDiscovery
              .mainFromAnnotation(occurrence, textDocument)
              .flatMap(classes.get(_))
            List(normal, fromAnnot).flatten
          }
        } yield mainClass
        if (mains.nonEmpty) {
          verifyMain(Map(buildTarget -> mains.toList), params)
        } else if (tests.nonEmpty) {
          Future {
            val params = new b.DebugSessionParams(singletonList(buildTarget))
            params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
            params.setData(tests.asJava.toJson)
            params
          }

        } else {
          Future.failed(NoRunOptionException)
        }
      }
  }

  private def createMainParams(
      params: DebugDiscoveryParams,
      main: b.ScalaMainClass,
      buildTargetIdentifier: b.BuildTargetIdentifier,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val env = Option(params.env).toList.flatMap(DebugProvider.createEnvList)
    DebugProvider.createMainParams(
      workspace,
      main,
      buildTargetIdentifier,
      Option(params.args),
      Option(params.jvmOptions),
      env,
      Option(params.envFile),
    )
  }

  /**
   * Given a BuildTargetIdentifier either get the displayName of that build
   * target or default to the full URI to display to the user.
   */
  private def displayName(buildTargetIdentifier: b.BuildTargetIdentifier) =
    buildTargets
      .info(buildTargetIdentifier)
      .map(_.getDisplayName)
      .getOrElse(buildTargetIdentifier.getUri)

  private def requestMain(
      mainClasses: List[b.ScalaMainClass]
  )(implicit ec: ExecutionContext): Future[b.ScalaMainClass] = {
    languageClient
      .metalsQuickPick(
        new MetalsQuickPickParams(
          mainClasses.map { m =>
            val cls = m.getClassName()
            new MetalsQuickPickItem(cls, cls)
          }.asJava,
          placeHolder = Messages.MainClass.message,
        )
      )
      .asScala
      .collect { case Some(choice) =>
        mainClasses.find { clazz =>
          clazz.getClassName() == choice.itemId
        }
      }
      .collect { case Some(main) => main }
  }

}

object DebugDiscovery {

  /**
   * Given an occurence and a text document return the symbol of a main method
   * that could be defined using the Scala 3 @main annotation.
   *
   * @param occurrence The symbol occurence you're checking against the document.
   * @param textDocument The document of the current file.
   * @return Possible symbol name of main.
   */
  def mainFromAnnotation(
      occurrence: SymbolOccurrence,
      textDocument: TextDocument,
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
                  rng.endCharacter - range.endCharacter,
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

  import scala.meta.internal.semanticdb.Scala._

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
              Descriptor.Term(_),
            ),
            Descriptor.Method(name, _),
          ) =>
        val converted = GlobalSymbol(owner, Descriptor.Term(name))
        Some(converted.value)
      case _ =>
        None
    }
  }
}
