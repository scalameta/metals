package scala.meta.internal.metals

import java.sql.Connection

import scala.meta.internal.metals.JdbcEnrichments._

class ChosenBuildTool(conn: () => Connection) {
  def selectedBuildTool(): Option[String] = {
    conn()
      .query(
        "select * from chosen_build_tool LIMIT 1;"
      )(_ => ()) { _.getString("build_tool") }
      .headOption
  }
  def chooseBuildTool(buildTool: String): Int = {
    conn().update {
      "insert into chosen_build_tool values (?);"
    } { stmt => stmt.setString(1, buildTool) }
  }

  def reset(): Unit = {
    conn().update("delete from chosen_build_tool") { _ => () }
  }
}
