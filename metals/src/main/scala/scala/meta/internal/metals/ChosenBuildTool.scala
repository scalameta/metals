package scala.meta.internal.metals

import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.metals.JdbcEnrichments._

class ChosenBuildTool(conn: () => Connection) {

  private val currentTool: AtomicReference[Option[String]] =
    new AtomicReference(
      None
    )

  def selectedBuildTool(): Option[String] = {
    currentTool.get() match {
      case selected @ Some(_) =>
        selected
      case None =>
        val selected = conn()
          .query(
            "select * from chosen_build_tool LIMIT 1;"
          )(_ => ()) { _.getString("build_tool") }
          .headOption

        selected.flatMap(toolName =>
          currentTool.updateAndGet {
            case None => Some(toolName)
            case some => some
          }
        )
    }
  }

  def chooseBuildTool(buildTool: String): Int = synchronized {
    reset()
    currentTool.set(Some(buildTool))
    conn().update {
      "insert into chosen_build_tool values (?);"
    } { stmt => stmt.setString(1, buildTool) }
  }

  def reset(): Unit = {
    currentTool.set(None)
    conn().update("delete from chosen_build_tool;") { _ => () }
  }
}
