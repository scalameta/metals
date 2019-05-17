package scala.meta.internal.eval

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.eval.JvmDebugProtocol.LaunchParameters
import scala.meta.internal.metals.MetalsEnrichments._

final class JvmDebugAdapter(implicit val ex: ExecutionContext)
    extends DebugAdapter {
  private val client = new DelegatingDebugClient
  private val sessionFactory = new DebugSessionFactory(client)

  private var debugSession: Option[DebugSession] = None
  private var isRestartRequested: Boolean = false

  def initialize(
      args: InitializeRequestArguments
  ): CompletableFuture[Capabilities] = {
    val capabilities = new Capabilities()
    capabilities.setSupportsRestartRequest(true)
    CompletableFuture.completedFuture(capabilities)
  }

  def launch(params: LaunchParameters): CompletableFuture[Unit] = {
    val session = sessionFactory.create(params)

    session.exitCode.foreach { status =>
      client.exited(JvmDebugProtocol.exitedEvent(status))
      if (!isRestartRequested)
        client.terminated(new TerminatedEventArguments)

      debugSession = None
    }

    debugSession = Some(session)
    CompletableFuture.completedFuture(())
  }

  def restart(params: RestartArguments): CompletableFuture[Unit] = {
    debugSession match {
      case None => CompletableFuture.completedFuture(())
      case Some(session) =>
        isRestartRequested = true
        val task =
          for {
            _ <- shutdownSession()
            _ <- launch(session.parameters).asScala
          } yield {
            isRestartRequested = false
          }

        task.asJava
    }
  }

  def disconnect(args: DisconnectArguments): CompletableFuture[Unit] =
    shutdownSession().asJava

  def terminate(args: TerminateArguments): CompletableFuture[Unit] =
    shutdownSession().asJava

  override def setClient(client: IDebugProtocolClient): Unit = {
    this.client.setUnderlying(client)
  }

  override def shutdown(): Unit =
    shutdownSession().asJava.get(10, TimeUnit.SECONDS)

  private def shutdownSession(): Future[Unit] =
    debugSession match {
      case Some(session) =>
        for {
          _ <- session.shutdown()
        } yield {
          debugSession = None
        }
      case _ => Future.successful(())
    }
}

object JvmDebugAdapter {
  def apply(
      client: IDebugProtocolClient
  )(implicit ec: ExecutionContext): JvmDebugAdapter = {
    val adapter = new JvmDebugAdapter()
    adapter.setClient(client)
    adapter
  }
}
