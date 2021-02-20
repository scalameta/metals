package scala.meta.internal.metals.debug

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections.singletonList
import java.util.concurrent.TimeUnit
import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.DebugUnresolvedAttachRemoteParams
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.JsonParser
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.UnresolvedDebugSessionParams
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.StacktraceAnalyzer
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.parsing.ClassFinder
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import scala.meta.internal.metals.DebugFullyUnresolvedParams
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.MessageActionItem
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.config.RunType._
import scala.meta.internal.mtags.Semanticdbs

class DebugProvider(
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider,
    buildServer: () => Option[BuildServerConnection],
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    compilations: Compilations,
    languageClient: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    statusBar: StatusBar,
    classFinder: ClassFinder,
    index: OnDemandSymbolIndex,
    stacktraceAnalyzer: StacktraceAnalyzer,
    icons: Icons,
    semanticdbs: Semanticdbs
) {

  lazy val buildTargetClassesFinder = new BuildTargetClassesFinder(
    buildTargets,
    buildTargetClasses,
    index
  )

  def start(
      parameters: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    Future.fromTry(parseSessionName(parameters)).flatMap { sessionName =>
      val inetAddress = InetAddress.getByName("127.0.0.1")
      val proxyServer = new ServerSocket(0, 50, inetAddress)
      val host = InetAddresses.toUriString(proxyServer.getInetAddress)
      val port = proxyServer.getLocalPort
      proxyServer.setSoTimeout(10 * 1000)
      val uri = URI.create(s"tcp://$host:$port")
      val connectedToServer = Promise[Unit]()

      val awaitClient =
        () => Future(proxyServer.accept())

      val jvmOptionsTranslatedParams = translateJvmParams(parameters)
      // long timeout, since server might take a while to compile the project
      val connectToServer = () => {
        val targets = jvmOptionsTranslatedParams.getTargets().asScala

        compilations.compilationFinished(targets).flatMap { _ =>
          buildServer()
            .map(_.startDebugSession(jvmOptionsTranslatedParams))
            .getOrElse(BuildServerUnavailableError)
            .withTimeout(60, TimeUnit.SECONDS)
            .map { uri =>
              val socket = connect(uri)
              connectedToServer.trySuccess(())
              socket
            }
            .recover { case exception =>
              connectedToServer.tryFailure(exception)
              throw exception
            }
        }
      }

      val proxyFactory = { () =>
        val targets = parameters.getTargets.asScala
          .map(_.getUri)
          .map(new BuildTargetIdentifier(_))
        val sourcePathProvider = new SourcePathProvider(
          definitionProvider,
          buildTargets,
          targets.toList
        )
        DebugProxy
          .open(
            sessionName,
            sourcePathProvider,
            awaitClient,
            connectToServer,
            classFinder,
            stacktraceAnalyzer
          )
      }
      val server = new DebugServer(sessionName, uri, proxyFactory)

      server.listen.andThen { case _ => proxyServer.close() }

      connectedToServer.future.map(_ => server)
    }
  }

  /**
   * Given a BuildTargetIdentifier either get the displayName of that build
   * target or defaul to the full URI to display to the user.
   */
  private def displayName(buildTargetIdentifier: BuildTargetIdentifier) =
    buildTargets
      .info(buildTargetIdentifier)
      .map(_.getDisplayName)
      .getOrElse(buildTargetIdentifier.getUri)

  private def requestMain(
      mainClasses: List[ScalaMainClass]
  )(implicit ec: ExecutionContext): Future[ScalaMainClass] = {
    languageClient
      .showMessageRequest(
        Messages.MainClass.params(mainClasses)
      )
      .asScala
      .map { choice =>
        mainClasses.find { clazz =>
          new MessageActionItem(clazz.getClassName()) == choice
        }
      }
      .collect { case Some(main) => main }
  }

  private def createMainParams(
      buildTarget: BuildTargetIdentifier,
      classes: List[ScalaMainClass]
  )(implicit ec: ExecutionContext) = {
    classes match {
      case Nil =>
        Future.failed(
          BuildTargetContainsNoMainException(displayName(buildTarget))
        )
      case main :: Nil =>
        Future {
          new b.DebugSessionParams(
            singletonList(buildTarget),
            b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
            main.toJson
          )
        }
      case multiple =>
        requestMain(multiple).map { main =>
          new b.DebugSessionParams(
            singletonList(buildTarget),
            b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
            main.toJson
          )
        }
    }
  }

  /**
   * Given fully unresolved params this figures out the runType that was passed
   * in and then discovers either the main methods for the build target the
   * path belongs to or finds the tests for the current file or build target
   */
  def debugDiscovery(
      params: DebugFullyUnresolvedParams
  )(implicit ec: ExecutionContext) = {
    val runTypeO = RunType.fromString(params.runType)
    val path = AbsolutePath.fromAbsoluteUri(new URI(params.path))
    val buildTargetO = buildTargets.inverseSources(path)

    lazy val mainClasses = (bti: BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).mainClasses.values.toList

    lazy val testClasses = (bti: BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).testClasses

    lazy val textDocumentO =
      semanticdbs.textDocument(path).documentIncludingStale

    val result = (runTypeO, buildTargetO) match {
      case _ if buildClient.buildHasErrors =>
        Future.failed(WorkspaceErrorsException)
      case (_, None) =>
        Future.failed(BuildTargetNotFoundForPathException(path))
      case (None, _) =>
        Future.failed(RunType.UnknownRunTypeException(params.runType))
      // TODO make sure env variables are set for run
      case (Some(Run), Some(target)) =>
        createMainParams(target, mainClasses(target))
      case (Some(TestFile), Some(target)) if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("file", path.toString())
        )
      case (Some(TestTarget), Some(target)) if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("build target", displayName(target))
        )
      case (Some(TestFile), Some(target)) =>
        textDocumentO
          .fold[Future[Seq[BuildTargetClasses.ClassName]]] {
            Future.failed(new Exception("Semanticdb not found for path."))
          } { textDocument =>
            Future {
              for {
                occurence <- textDocument.occurrences
                if occurence.role.isDefinition
                symbol = occurence.symbol
                testClass <- testClasses(target).get(symbol)
              } yield testClass
            }
          }
          .map { tests =>
            new b.DebugSessionParams(
              singletonList(target),
              b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
              tests.asJava.toJson
            )
          }
      case (Some(TestTarget), Some(target)) =>
        Future {
          new b.DebugSessionParams(
            singletonList(target),
            b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
            testClasses(target).values.toList.asJava.toJson
          )
        }
    }

    result.failed.foreach(reportErrors)
    result
  }

  def resolveMainClassParams(
      params: DebugUnresolvedMainClassParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = withRebuildRetry(() =>
      buildTargetClassesFinder
        .findMainClassAndItsBuildTarget(
          params.mainClass,
          Option(params.buildTarget)
        )
    ).flatMap {
      case (_, target) :: _ if buildClient.buildHasErrors(target.getId()) =>
        Future.failed(WorkspaceErrorsException)
      case (clazz, target) :: others =>
        if (others.nonEmpty) {
          reportOtherBuildTargets(
            clazz.getClassName(),
            target,
            others,
            "main"
          )
        }
        clazz.setArguments(Option(params.args).getOrElse(List().asJava))
        clazz.setJvmOptions(
          Option(params.jvmOptions).getOrElse(List().asJava)
        )

        val env: List[String] =
          if (params.env != null)
            params.env.asScala.map { case (key, value) =>
              s"$key=$value"
            }.toList
          else
            Nil

        val envFromFile: Future[List[String]] =
          if (params.envFile != null) {
            val path = AbsolutePath(params.envFile)(workspace)
            DotEnvFileParser
              .parse(path)
              .map(_.map { case (key, value) => s"$key=$value" }.toList)
          } else Future.successful(List.empty)

        envFromFile.map { envFromFile =>
          clazz.setEnvironmentVariables((envFromFile ::: env).asJava)
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
            clazz.toJson
          )
        }

      //should not really happen due to
      //`findMainClassAndItsBuildTarget` succeeding with non-empty list
      case Nil => Future.failed(new ju.NoSuchElementException(params.mainClass))
    }
    result.failed.foreach(reportErrors)
    result
  }

  def resolveTestClassParams(
      params: DebugUnresolvedTestClassParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = withRebuildRetry(() => {
      buildTargetClassesFinder
        .findTestClassAndItsBuildTarget(
          params.testClass,
          Option(params.buildTarget)
        )
    }).flatMap {
      case (_, target) :: _ if buildClient.buildHasErrors(target.getId()) =>
        Future.failed(WorkspaceErrorsException)
      case (clazz, target) :: others =>
        if (others.nonEmpty) {
          reportOtherBuildTargets(
            clazz,
            target,
            others,
            "test"
          )
        }
        Future.successful(
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
            singletonList(clazz).toJson
          )
        )
      //should not really happen due to
      //`findMainClassAndItsBuildTarget` succeeding with non-empty list
      case Nil => Future.failed(new ju.NoSuchElementException(params.testClass))
    }
    result.failed.foreach(reportErrors)
    result
  }

  def resolveAttachRemoteParams(
      params: DebugUnresolvedAttachRemoteParams
  ): Future[b.DebugSessionParams] =
    buildTargets.findByDisplayName(params.buildTarget) match {
      case Some(target) =>
        Future.successful(
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE,
            Unit.toJson
          )
        )
      case None =>
        Future.failed(new ju.NoSuchElementException(params.buildTarget))
    }

  private val reportErrors: PartialFunction[Throwable, Unit] = {
    case _ if buildClient.buildHasErrors =>
      statusBar.addMessage(Messages.DebugErrorsPresent(icons))
      languageClient.metalsExecuteClientCommand(
        new ExecuteCommandParams(
          ClientCommands.FocusDiagnostics.id,
          Nil.asJava
        )
      )
    case t: ClassNotFoundException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.invalidClass(t.getMessage())
      )
    case ClassNotFoundInBuildTargetException(cls, buildTarget) =>
      languageClient.showMessage(
        Messages.DebugClassNotFound
          .invalidTargetClass(cls, buildTarget.getDisplayName())
      )
    case BuildTargetNotFoundException(target) =>
      languageClient.showMessage(
        Messages.DebugClassNotFound
          .invalidTarget(target)
      )
    // TODO make a generic pass throw for show
    case e: BuildTargetNotFoundForPathException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.show(e.getMessage())
      )
    case e: BuildTargetContainsNoMainException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.show(e.getMessage())
      )
    case e: NoTestsFoundException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.show(e.getMessage())
      )
    case e: RunType.UnknownRunTypeException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.show(e.getMessage())
      )
  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    parameters.getData match {
      case json: JsonElement =>
        parameters.getDataKind match {
          case b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
            json.as[b.ScalaMainClass].map(_.getClassName)
          case b.DebugSessionParamsDataKind.SCALA_TEST_SUITES =>
            json.as[ju.List[String]].map(_.asScala.sorted.mkString(";"))
          case b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE =>
            Success("attach-remote-debug-session")
        }
      case data =>
        val dataType = data.getClass.getSimpleName
        Failure(new IllegalStateException(s"Data is $dataType. Expecting json"))
    }
  }

  private def translateJvmParams(
      parameters: b.DebugSessionParams
  ): b.DebugSessionParams = {
    parameters.getData match {
      case json: JsonElement
          if parameters.getDataKind == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json.as[b.ScalaMainClass].foreach { main =>
          if (main.getEnvironmentVariables() == null) {
            main.setEnvironmentVariables(Nil.asJava)
          }
          parameters.setData(main.toJsonObject)
        }
        parameters
      case _ =>
        parameters
    }
  }
  private def connect(uri: URI): Socket = {
    val socket = new Socket()

    val address = new InetSocketAddress(uri.getHost, uri.getPort)
    val timeout = TimeUnit.SECONDS.toMillis(10).toInt
    socket.connect(address, timeout)

    socket
  }

  private def withRebuildRetry[A](
      f: () => Try[A]
  )(implicit ec: ExecutionContext): Future[A] = {
    Future.fromTry(f()).recoverWith {
      case ClassNotFoundInBuildTargetException(_, buildTarget) =>
        val target = Seq(buildTarget.getId())
        for {
          _ <- compilations.compileTargets(target)
          _ <- buildTargetClasses.rebuildIndex(target)
          result <- Future.fromTry(f())
        } yield result
      case _: ClassNotFoundException =>
        val allTargets = buildTargets.allBuildTargetIds
        for {
          _ <- compilations.compileTargets(allTargets)
          _ <- buildTargetClasses.rebuildIndex(allTargets)
          result <- Future.fromTry(f())
        } yield result
    }
  }

  private def reportOtherBuildTargets(
      className: String,
      buildTarget: b.BuildTarget,
      others: List[(_, b.BuildTarget)],
      mainOrTest: String
  ) = {
    val otherTargets = others.map(_._2.getDisplayName())
    languageClient.showMessage(
      new MessageParams(
        MessageType.Warning,
        UnresolvedDebugSessionParams
          .runningClassMultipleBuildTargetsMessage(
            className,
            buildTarget.getDisplayName(),
            otherTargets,
            mainOrTest
          )
      )
    )
  }

  private lazy val BuildServerUnavailableError =
    Future.failed(new IllegalStateException("Build server unavailable"))

}

object DebugParametersJsonParsers {
  lazy val debugSessionParamsParser = new JsonParser.Of[b.DebugSessionParams]
  lazy val mainClassParamsParser =
    new JsonParser.Of[DebugUnresolvedMainClassParams]
  lazy val testClassParamsParser =
    new JsonParser.Of[DebugUnresolvedTestClassParams]
  lazy val attachRemoteParamsParser =
    new JsonParser.Of[DebugUnresolvedAttachRemoteParams]
  lazy val unresolvedParamsParser =
    new JsonParser.Of[DebugFullyUnresolvedParams]
}

case object WorkspaceErrorsException
    extends Exception(
      s"Cannot run class, since the workspace has errors."
    )
