package scala.meta.internal.metals

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.io.AbsolutePath

class BspStatus(client: MetalsLanguageClient, isBspStatusProvider: Boolean) {
  val focusedFolder: AtomicReference[Option[AbsolutePath]] =
    new AtomicReference(None)
  val messages: java.util.Map[AbsolutePath, MetalsStatusParams] =
    Collections.synchronizedMap(
      new java.util.HashMap[AbsolutePath, MetalsStatusParams]
    )

  def status(folder: AbsolutePath, params: MetalsStatusParams): Unit = {
    messages.put(folder, params)
    if (focusedFolder.get().isEmpty || focusedFolder.get().contains(folder)) {
      client.metalsStatus(params)
    }
  }

  def focus(folder: AbsolutePath): Unit = {
    if (isBspStatusProvider) {
      val prev = focusedFolder.getAndSet(Some(folder))
      if (!prev.contains(folder)) {
        client.metalsStatus(
          messages.getOrDefault(folder, ConnectionBspStatus.disconnectedParams)
        )
      }
    }
  }
}
