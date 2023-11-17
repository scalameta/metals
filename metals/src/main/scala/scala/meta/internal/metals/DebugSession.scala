package scala.meta.internal.metals

import javax.annotation.Nullable

final case class DebugSession(
    @Nullable name: String,
    @Nullable uri: String,
    @Nullable error: String,
)

object DebugSession {

  def success(name: String, uri: String): DebugSession = {
    DebugSession(name, uri, null)
  }
  def failure(message: String): DebugSession = {
    DebugSession(null, null, message)
  }
}
