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
    val selected = currentTool
      .get()
      .orElse(
        conn()
          .query(
            "select * from chosen_build_tool LIMIT 1;"
          )(_ => ()) { _.getString("build_tool") }
          .headOption
      )
    selected.foreach(toolName => currentTool.set(Some(toolName)))
    selected
  }

  def chooseBuildTool(buildTool: String): Int = synchronized {
    reset()
    currentTool.set(Some(buildTool))
    conn().update {
      "insert into chosen_build_tool values (?);"
    } { stmt => stmt.setString(1, buildTool) }
  }

  def reset(): Unit = {
    conn().update("delete from chosen_build_tool;") { _ => () }
  }
}
