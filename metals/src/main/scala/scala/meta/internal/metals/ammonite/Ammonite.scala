package scala.meta.internal.metals.ammonite

import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.ammonite.Ammonite.AmmoniteMetalsException
import scala.meta.internal.metals.clients.language.ForwardingMetalsBuildClient
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import ammrunner.AmmoniteFetcher
import ammrunner.VersionsOption
import ammrunner.error.AmmoniteFetcherException
import ammrunner.{Command => AmmCommand}
import ammrunner.{Versions => AmmVersions}
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Position
import org.eclipse.{lsp4j => l}

final class Ammonite(
    buffers: Buffers,
    compilers: Compilers,
    compilations: Compilations,
    statusBar: StatusBar,
    diagnostics: Diagnostics,
    tables: () => Tables,
    languageClient: MetalsLanguageClient,
    buildClient: ForwardingMetalsBuildClient,
    userConfig: () => UserConfiguration,
    indexWorkspace: () => Future[Unit],
    workspace: () => AbsolutePath,
    focusedDocument: () => Option[AbsolutePath],
    config: MetalsServerConfig,
    scalaVersionSelector: ScalaVersionSelector,
    parseTreesAndPublishDiags: Seq[AbsolutePath] => Future[Unit],
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  val buildTargetsData = new TargetData

  def buildServer: Option[BuildServerConnection] =
    buildServer0
  def lastImportedBuild: ImportedBuild =
    lastImportedBuild0

  private var buildServer0 = Option.empty[BuildServerConnection]
  private var lastImportedBuild0 = ImportedBuild.empty
  private var lastImportVersions = VersionsOption(None, None)

  private val cancelables = new MutableCancelable()
  private val isCancelled = new AtomicBoolean(false)
  def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      val buildShutdown = buildServer0 match {
        case Some(build) => build.shutdown()
        case None => Future.unit
      }
      try cancelables.cancel()
      catch {
        case NonFatal(_) =>
      }
      try buildShutdown.asJava.get(100, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
      }
    }
  }

  def loaded(path: AbsolutePath): Boolean =
    path.isAmmoniteScript && {
      val uri = path.toNIO.toUri.toASCIIString
      lastImportedBuild0.targetUris.contains(uri)
    }

  def importBuild(): Future[Unit] =
    buildServer0 match {
      case None =>
        Future.failed(new Exception("No Ammonite build server running"))
      case Some(conn) =>
        compilers.cancel()
        for {
          build0 <- statusBar.trackFuture(
            "Importing Ammonite scripts",
            ImportedBuild.fromConnection(conn),
          )
          _ = {
            lastImportedBuild0 = build0
            val workspace0 = workspace()
            val targets = build0.workspaceBuildTargets.getTargets.asScala
            val connections =
              targets.iterator.map(_.getId).map((_, conn)).toList
            for {
              target <- targets
              classDirUriOpt = build0.scalacOptions.getItems.asScala
                .find(_.getTarget == target.getId)
                .map(_.getClassDirectory)
              classDirUri <- classDirUriOpt
            } {
              val scPath =
                AbsolutePath.fromAbsoluteUri(new URI(target.getId.getUri))
              val rel = scPath.toRelative(workspace0)
              val scalaPath = Paths
                .get(new URI(classDirUri))
                .getParent
                .resolve(
                  s"src/ammonite/$$file/${rel.toString.stripSuffix(".sc")}.scala"
                )
              val mapped =
                new Ammonite.AmmoniteMappedSource(AbsolutePath(scalaPath))
              buildTargetsData.addMappedSource(scPath, mapped)
            }
            buildTargetsData.resetConnections(connections)
          }
          _ <- indexWorkspace()
          toCompile = buffers.open.toSeq.filter(_.isAmmoniteScript)
          _ <- Future.sequence(
            compilations
              .cascadeCompileFiles(toCompile) ::
              compilers.load(toCompile) ::
              parseTreesAndPublishDiags(toCompile) ::
              Nil
          )
        } yield ()
    }

  private def connectToNewBuildServer(
      build: BuildServerConnection
  ): Future[BuildChange] = {
    scribe.info(s"Connected to Ammonite Build server v${build.version}")
    cancelables.add(build)
    buildServer0 = Some(build)
    for {
      _ <- importBuild()
    } yield BuildChange.Reconnected
  }

  private def disconnectOldBuildServer(): Future[Unit] = {
    if (buildServer0.isDefined)
      scribe.info("disconnected: ammonite build server")
    buildServer0 match {
      case None => Future.unit
      case Some(value) =>
        buildServer0 = None
        lastImportedBuild0 = ImportedBuild.empty
        cancelables.cancel()
        diagnostics.resetAmmoniteScripts()
        value.shutdown()
    }
  }

  private def command(
      path: AbsolutePath
  ): Either[AmmoniteFetcherException, (AmmCommand, AbsolutePath)] = {
    val it = path.toInputFromBuffers(buffers).value.linesIterator
    val versionsOpt = VersionsOption.fromScript(it)
    val versions = versionsOpt
      .orElse(lastImportVersions)
      .getOrElse(
        AmmVersions(
          ammoniteVersion = BuildInfo.ammoniteVersion,
          scalaVersion =
            scalaVersionSelector.fallbackScalaVersion(isAmmonite = true),
        )
      )
    val res = Try {
      AmmoniteFetcher(versions)
        .withInterpOnly(false)
        .withProgressBars(false)
        .withResolutionParams(
          coursierapi.ResolutionParams
            .create()
            .withScalaVersion(versions.scalaVersion)
        )
        .command()
    } match {
      case Success(v) => v
      // some coursier exceptions are not wrapped in the Fetcher exception
      case Failure(e) =>
        Left(new AmmoniteFetcherException(e.getMessage(), e) {})
    }
    res match {
      case Left(e) =>
        scribe.error(
          s"Error getting Ammonite ${versions.ammoniteVersion} (scala ${versions.scalaVersion})",
          e,
        )
        Left(e)
      case Right(command) =>
        lastImportVersions = VersionsOption(
          Some(versions.ammoniteVersion),
          Some(versions.scalaVersion),
        )
        Right((command, path))
    }
  }

  def reload(): Future[Unit] = stop().asScala.flatMap(_ => start())

  def start(doc: Option[AbsolutePath] = None): Future[Unit] = {

    disconnectOldBuildServer().onComplete {
      case Failure(e) =>
        scribe.warn("Error disconnecting old Ammonite build server", e)
      case Success(()) =>
    }

    val docs = doc.toSeq ++ focusedDocument().toSeq ++ buffers.open.toSeq
    val commandScriptOpt = docs
      .find(_.isAmmoniteScript)
      .map { ammScript => Future.fromTry(command(ammScript).toTry) }
      .getOrElse {
        val msg =
          if (docs.isEmpty) "No Ammonite script is opened"
          else "No open document is not an Ammonite script"
        scribe.error(msg)
        Future.failed(new AmmoniteMetalsException(msg))
      }

    commandScriptOpt
      .flatMap { case (command, script) =>
        val extraScripts = buffers.open.toVector
          .filter(path => path.isAmmoniteScript && path != script)
        val jvmOpts = userConfig().ammoniteJvmProperties.getOrElse(Nil)
        val commandWithJVMOpts =
          command.addJvmArgs(jvmOpts: _*)
        val futureConn = BuildServerConnection.fromSockets(
          workspace(),
          buildClient,
          languageClient,
          () =>
            Ammonite
              .socketConn(
                commandWithJVMOpts,
                script +: extraScripts,
                workspace(),
              ),
          tables().dismissedNotifications.ReconnectAmmonite,
          config,
          "Ammonite",
        )
        for {
          conn <- futureConn
          _ <- connectToNewBuildServer(conn)
        } yield ()
      }
      .recoverWith {
        case t @ (_: AmmoniteFetcherException | _: AmmoniteMetalsException) =>
          languageClient.showMessage(Messages.errorFromThrowable(t))
          Future(())
      }
  }

  def stop(): CompletableFuture[Object] = {
    lastImportVersions = VersionsOption(None, None)
    disconnectOldBuildServer().asJavaObject
  }
}

object Ammonite {

  def startTag: String =
    "/*<start>*/\n"

  case class AmmoniteMetalsException(msg: String) extends Exception(msg)

  def isAmmBuildTarget(id: BuildTargetIdentifier): Boolean =
    id.getUri.endsWith(".sc")

  private def adjustPosition(scalaCode: String): Position => Position = {
    val startIdx = scalaCode.indexOf(startTag)
    if (startIdx >= 0) {
      val linesBefore = scalaCode.lineAtIndex(startIdx + startTag.length)
      pos =>
        if (pos.getLine < linesBefore)
          new Position(0, 0)
        else
          new Position(pos.getLine - linesBefore, pos.getCharacter)
    } else
      identity _
  }

  def adjustLspData(scalaCode: String): AdjustLspData =
    AdjustedLspData.create(adjustPosition(scalaCode))

  private val threadCounter = new AtomicInteger
  private def logOutputThread(
      is: InputStream,
      stopSendingOutput: => Boolean,
  ): Thread =
    new Thread(
      s"ammonite-bsp-server-stderr-to-metals-log-${threadCounter.incrementAndGet()}"
    ) {
      setDaemon(true)
      val buf = Array.ofDim[Byte](2048)
      override def run(): Unit = {
        var read = 0
        while ({
          !stopSendingOutput && {
            read = is.read(buf)
            read >= 0
          }
        }) {
          if (read > 0) {
            val content =
              new String(buf, 0, read, Charset.defaultCharset())
            scribe.info("Ammonite: " + content)
          }
        }
      }
    }

  private def socketConn(
      command: AmmCommand,
      scripts: Seq[AbsolutePath],
      workspace: AbsolutePath,
  )(implicit ec: ExecutionContext): Future[SocketConnection] =
    // meh, blocks on random ec
    Future {
      val proc = command
        .withArgs(Seq("--bsp") ++ scripts.map(_.toNIO.toString))
        .run { proc0 =>
          proc0
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .directory(workspace.toFile)
        }
      val os = new ClosableOutputStream(proc.getOutputStream, "Ammonite")
      var stopSendingOutput = false
      val sendOutput =
        Ammonite.logOutputThread(proc.getErrorStream, stopSendingOutput)
      sendOutput.start()
      val finished = Promise[Unit]()
      Future {
        proc.waitFor()
        finished.success(())
        ()
      }.onComplete {
        case Success(()) =>
        case f @ Failure(_) => finished.tryComplete(f)
      }
      SocketConnection(
        "Ammonite",
        os,
        proc.getInputStream,
        List(
          Cancelable { () => proc.destroyForcibly() },
          Cancelable { () => stopSendingOutput = true },
        ),
        finished,
      )
    }

  private class AmmoniteMappedSource(val path: AbsolutePath)
      extends TargetData.MappedSource {
    def update(
        content: String
    ): (Input.VirtualFile, l.Position => l.Position, AdjustLspData) = {

      val input = {
        val input0 = path.toInput
        // ensuring the path ends with ".amm.sc.scala" so that the PC has a way to know
        // what we're giving it originates from an Ammonite script
        input0.copy(path = input0.path.stripSuffix(".scala") + ".amm.sc.scala")
      }

      /*

      When given a script like

          case class Bar(xs: Vector[String])

      Ammonite generates a .scala file like

          package ammonite
          package $file

          import _root_.ammonite.interp.api.InterpBridge.{value => interp}

          object `main-1`{
            /*<script>*/case class Bar(xs: Vector[String])/*</script>*/ /*<generated>*/
            def $main() = { scala.Iterator[String]() }
            override def toString = "main$minus1"
            /*</generated>*/
          }

      When the script is being edited, we re-generate on-the-fly a valid .scala file ourselves
      from the one originally generated by Ammonite. The result should be a valid scala file, that
      we can pass to the PC.

      In order to update the .scala file above, we:
      - remove the section between '/*<generated>*/' and '/*</generated>*/'
      - replace the section between '/*<script>*/' and '/*</script>*/' by the new content of the script

       */

      val updatedContent = input.value
        .replaceAllBetween("/*<generated>*/", "/*</generated>*/")("")
        .replaceAllBetween("/*<script>*/", "/*</script>*/")(
          Ammonite.startTag + content
        )
      val updatedInput = input.copy(value = updatedContent)

      val scriptStartIdx =
        updatedContent.indexOf(Ammonite.startTag) + Ammonite.startTag.length
      val addedLineCount = updatedContent.lineAtIndex(scriptStartIdx)
      val updatePos: l.Position => l.Position = position =>
        new l.Position(addedLineCount + position.getLine, position.getCharacter)
      val adjustLspData0 = adjustLspData(updatedContent)
      (updatedInput, updatePos, adjustLspData0)
    }
  }

  val name = "Ammonite"
}
