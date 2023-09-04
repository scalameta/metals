package scala.meta.internal.metals

import java.sql.Connection

import scala.meta.internal.mtags.OverriddenSymbol
import scala.meta.io.AbsolutePath

//TODO:: implement
final class JarOverridden(conn: () => Connection) {

  def getOverriddenInfo(
      path: AbsolutePath
  ): Option[List[(AbsolutePath, List[(String, List[OverriddenSymbol])])]] = None

  def putOverriddenInfo(
      path: AbsolutePath,
      overridden: List[(AbsolutePath, List[(String, List[OverriddenSymbol])])],
  ): Int = 0
}
