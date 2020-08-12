package scala.meta.internal.metals.debug

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
import scala.util.Try

import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.JsonParser
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.UnresolvedDebugSessionParams
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.mtags.OnDemandSymbolIndex

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

class DebugProvider(
    definitionProvider: DefinitionProvider,
    buildServer: () => Option[BuildServerConnection],
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    compilations: Compilations,
    languageClient: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    statusBar: StatusBar,
    compilers: Compilers,
    index: OnDemandSymbolIndex
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
      val proxyServer = new ServerSocket(0)
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
        buildServer()
          .map(_.startDebugSession(jvmOptionsTranslatedParams))
          .getOrElse(BuildServerUnavailableError)
          .withTimeout(60, TimeUnit.SECONDS)
          .map { uri =>
            val socket = connect(uri)
            connectedToServer.trySuccess(())
            socket
          }
          .recover {
            case exception =>
              connectedToServer.tryFailure(exception)
              throw exception
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
            compilers
          )
      }
      val server = new DebugServer(sessionName, uri, proxyFactory)

      server.listen.andThen { case _ => proxyServer.close() }

      connectedToServer.future.map(_ => server)
    }
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
        Future.successful(
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
            clazz.toJson
          )
        )
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

  private val reportErrors: PartialFunction[Throwable, Unit] = {
    case _ if buildClient.buildHasErrors =>
      statusBar.addMessage(Messages.DebugErrorsPresent)
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

  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    parameters.getData match {
      case json: JsonElement =>
        parameters.getDataKind match {
          case "scala-main-class" =>
            json.as[b.ScalaMainClass].map(_.getClassName)
          case "scala-test-suites" =>
            json.as[ju.List[String]].map(_.asScala.sorted.mkString(";"))
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
      case json: JsonElement if parameters.getDataKind == "scala-main-class" =>
        json.as[b.ScalaMainClass].foreach { main =>
          val translated = main.getJvmOptions().asScala.map { param =>
            if (!param.startsWith("-J"))
              s"-J$param"
            else
              param
          }
          main.setJvmOptions(translated.asJava)
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
}

case object WorkspaceErrorsException
    extends Exception(
      s"Cannot run class, since the workspace has errors."
    )
