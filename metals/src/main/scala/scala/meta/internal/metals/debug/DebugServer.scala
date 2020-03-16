package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.{util => ju}
import java.util.concurrent.TimeUnit
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.DefinitionProvider
import scala.util.Failure
import scala.util.Try
import scala.meta.internal.metals.BuildTargetClasses
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.Messages.UnresolvedDebugSessionParams
import scala.util.Success
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import java.util.Collections.singletonList
import scala.meta.internal.metals.JsonParser._

final class DebugServer(
    val sessionName: String,
    val uri: URI,
    connect: () => Future[DebugProxy]
)(implicit ec: ExecutionContext)
    extends Cancelable {
  @volatile private var isCancelled = false
  @volatile private var proxy: DebugProxy = _

  lazy val listen: Future[Unit] = {
    def loop: Future[Unit] = {
      connect().flatMap { proxy =>
        this.proxy = proxy

        if (isCancelled) Future(proxy.cancel())
        else {
          proxy.listen.flatMap {
            case DebugProxy.Terminated => Future.unit
            case DebugProxy.Restarted => loop
          }
        }
      }
    }

    loop
  }

  override def cancel(): Unit = {
    isCancelled = true
    if (proxy != null) proxy.cancel()
  }
}

object DebugServer {
  import scala.meta.internal.metals.MetalsEnrichments._

  def start(
      parameters: b.DebugSessionParams,
      definitionProvider: DefinitionProvider,
      buildTargets: BuildTargets,
      buildServer: => Option[BuildServerConnection]
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    Future.fromTry(parseSessionName(parameters)).flatMap { sessionName =>
      val proxyServer = new ServerSocket(0)
      val host = InetAddresses.toUriString(proxyServer.getInetAddress)
      val port = proxyServer.getLocalPort
      val uri = URI.create(s"tcp://$host:$port")
      val connectedToServer = Promise[Unit]()

      val awaitClient =
        () => Future(proxyServer.accept()).withTimeout(10, TimeUnit.SECONDS)

      // long timeout, since server might take a while to compile the project
      val connectToServer = () => {
        buildServer
          .map(_.startDebugSession(parameters))
          .getOrElse(BuildServerUnavailableError)
          .withTimeout(60, TimeUnit.SECONDS)
          .map { uri =>
            val socket = connect(uri)
            connectedToServer.trySuccess(())
            socket
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
          .open(sessionName, sourcePathProvider, awaitClient, connectToServer)
      }
      val server = new DebugServer(sessionName, uri, proxyFactory)

      server.listen.andThen { case _ => proxyServer.close() }

      connectedToServer.future.map(_ => server)
    }
  }

  def resolveMainClassParams(
      params: DebugUnresolvedMainClassParams,
      buildTargets: BuildTargets,
      buildTargetClasses: BuildTargetClasses,
      showWarningMessage: String => Unit
  ): Try[b.DebugSessionParams] = {
    val classAndTarget = Option(params.buildTarget).fold {
      val classes =
        buildTargetClasses.findMainClassByName(params.mainClass)
      if (classes.nonEmpty) Success {
        val classAndTarget = classes.head
        if (classes.length > 1) {
          val className = classAndTarget._1.getClassName()
          val targetName =
            buildTargets.info(classAndTarget._2).get.getDisplayName
          val anotherTargets = classes.tail
            .map {
              case (_, id) =>
                buildTargets.info(id).get.getDisplayName
            }
          showWarningMessage(
            UnresolvedDebugSessionParams
              .runningClassMultipleBuildTargetsMessage(
                className,
                targetName,
                anotherTargets,
                "main"
              )
          )
        }
        classAndTarget
      }
      else
        Failure(new ClassNotFoundException(params.mainClass))
    } { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .fold {
          Failure[(b.ScalaMainClass, b.BuildTargetIdentifier)](
            new BuildTargetNotFoundException(targetName)
          ): Try[(b.ScalaMainClass, b.BuildTargetIdentifier)]
        } { target =>
          buildTargetClasses
            .classesOf(target.getId())
            .mainClasses
            .values
            .find(
              _.getClassName == params.mainClass
            )
            .fold {
              Failure[(b.ScalaMainClass, b.BuildTargetIdentifier)](
                new ClassNotFoundInBuildTargetException(
                  params.mainClass,
                  targetName
                )
              ): Try[(b.ScalaMainClass, b.BuildTargetIdentifier)]
            } { clazz => Success(clazz -> target.getId()) }
        }
    }
    classAndTarget.map {
      case (clazz, target) =>
        clazz.setArguments(Option(params.args).getOrElse(List().asJava))
        clazz.setJvmOptions(Option(params.jvmOptions).getOrElse(List().asJava))
        val dataKind =
          b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
        val data = clazz.toJson
        new b.DebugSessionParams(
          singletonList(target),
          dataKind,
          data
        )
    }
  }

  def resolveTestClassParams(
      params: DebugUnresolvedTestClassParams,
      buildTargets: BuildTargets,
      buildTargetClasses: BuildTargetClasses,
      showWarningMessage: String => Unit
  ): Try[b.DebugSessionParams] = {
    val classAndTarget = Option(params.buildTarget).fold {
      val classes =
        buildTargetClasses.findTestClassByName(params.testClass)
      if (classes.nonEmpty) Success {
        val classAndTarget = classes.head
        if (classes.length > 1) {
          val targetName =
            buildTargets.info(classAndTarget._2).get.getDisplayName
          val anotherTargets = classes.tail
            .map {
              case (_, id) =>
                buildTargets.info(id).get.getDisplayName
            }
          showWarningMessage(
            UnresolvedDebugSessionParams
              .runningClassMultipleBuildTargetsMessage(
                classAndTarget._1,
                targetName,
                anotherTargets,
                "test"
              )
          )
        }
        classAndTarget
      }
      else
        Failure(new ClassNotFoundException(params.testClass))
    } { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .fold {
          Failure[(String, b.BuildTargetIdentifier)](
            new BuildTargetNotFoundException(targetName)
          ): Try[(String, b.BuildTargetIdentifier)]
        } { target =>
          buildTargetClasses
            .classesOf(target.getId())
            .testClasses
            .values
            .find(
              _ == params.testClass
            )
            .fold {
              Failure[(String, b.BuildTargetIdentifier)](
                new ClassNotFoundInBuildTargetException(
                  params.testClass,
                  targetName
                )
              ): Try[(String, b.BuildTargetIdentifier)]
            } { clazz =>
              Success(
                clazz -> target.getId()
              )
            }
        }
    }
    classAndTarget.map {
      case (clazz, target) =>
        val dataKind =
          b.DebugSessionParamsDataKind.SCALA_TEST_SUITES
        val data = singletonList(clazz).toJson
        new b.DebugSessionParams(
          singletonList(target),
          dataKind,
          data
        )
    }
  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    import scala.meta.internal.metals.JsonParser._
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

  private def connect(uri: URI): Socket = {
    val socket = new Socket()

    val address = new InetSocketAddress(uri.getHost, uri.getPort)
    val timeout = TimeUnit.SECONDS.toMillis(10).toInt
    socket.connect(address, timeout)

    socket
  }

  private val BuildServerUnavailableError =
    Future.failed(new IllegalStateException("Build server unavailable"))
}

class BuildTargetNotFoundException(buildTargetName: String)
    extends Exception(s"Build target not found: $buildTargetName")

class ClassNotFoundInBuildTargetException(
    className: String,
    buildTargetName: String
) extends Exception(
      s"Class '$className' not found in build target '$buildTargetName'"
    )
