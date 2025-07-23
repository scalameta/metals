package scala.meta.internal.metals.debug

import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Cancelable

final class DebugServer(
    val id: String,
    val sessionName: String,
    val uri: URI,
    connect: () => Future[DebugProxy],
)(implicit ec: ExecutionContext)
    extends Cancelable {
  @volatile private var isCancelled = false
  @volatile private var proxy: DebugProxy = _
  def getProxy: Option[DebugProxy] = Option(proxy)

  scribe.info(
    s"[DebugServer] Created debug server id=$id, sessionName=$sessionName, uri=$uri"
  )

  lazy val listen: Future[Unit] = {
    scribe.info(
      s"[DebugServer] listen() called for session $sessionName (id=$id)"
    )
    def loop: Future[Unit] = {
      scribe.info(s"[DebugServer] loop() starting for session $sessionName")
      connect()
        .flatMap { proxy =>
          scribe.info(
            s"[DebugServer] connect() succeeded, got proxy for session $sessionName"
          )
          this.proxy = proxy
          scribe.info(
            s"[DebugServer] Line 24 reached - proxy assigned for session $sessionName"
          )

          if (isCancelled) {
            scribe.info(
              s"[DebugServer] Session $sessionName was cancelled, cancelling proxy"
            )
            Future(proxy.cancel())
          } else {
            scribe.info(
              s"[DebugServer] Starting proxy.listen for session $sessionName"
            )
            proxy.listen.flatMap {
              case DebugProxy.Terminated =>
                scribe.info(
                  s"[DebugServer] Proxy terminated for session $sessionName"
                )
                Future.unit
              case DebugProxy.Restarted =>
                scribe.info(
                  s"[DebugServer] Proxy restarted for session $sessionName, looping"
                )
                loop
            }
          }
        }
        .recover { case e: Exception =>
          scribe.error(
            s"[DebugServer] connect() failed for session $sessionName",
            e,
          )
          throw e
        }
    }

    loop
  }

  override def cancel(): Unit = {
    scribe.info(
      s"[DebugServer] cancel() called for session $sessionName (id=$id)"
    )
    isCancelled = true
    if (proxy != null) {
      scribe.info(s"[DebugServer] Cancelling proxy for session $sessionName")
      proxy.cancel()
    } else {
      scribe.info(s"[DebugServer] No proxy to cancel for session $sessionName")
    }
  }
}
