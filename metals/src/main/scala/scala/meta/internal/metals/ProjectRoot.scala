package scala.meta.internal.metals

import java.sql.Connection

import scala.meta.internal.metals.JdbcEnrichments._

class ProjectRoot(conn: () => Connection) {
  def relativePath(): Option[String] = {
    conn()
      .query(
        "select * from project_root LIMIT 1;"
      )(_ => ()) { _.getString("relative_path") }
      .headOption
  }
  def set(relativePath: Option[String]): Int = synchronized {
    reset()
    relativePath
      .map { relativePath =>
        conn().update {
          "insert into project_root values (?);"
        } { stmt => stmt.setString(1, relativePath) }
      }
      .getOrElse(0)
  }

  def reset(): Unit = {
    conn().update("delete from project_root;") { _ => () }
  }
}
