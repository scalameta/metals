package tests

import scala.meta.internal.semver.SemVer

object Compat {

  /**
   * Cases might be described as:
   *  - Starts with: "3.0.1" -> value OR "3.0" -> value OR "3" -> value
   *  - Greater or equal: ">=3.0.0" -> value
   */
  def forScalaVersion[A](
      scalaVersion: String,
      cases: Map[String, A],
  ): Option[A] = {
    val (startsWith, gt) =
      cases.partition { case (v, _) => !v.startsWith(">=") }

    val fromStartWith = {
      val filtered = startsWith
        .filter { case (v, _) => scalaVersion.startsWith(v) }
      if (filtered.nonEmpty) {
        val out = filtered.maxBy { case (v, _) => v.length() }._2
        Some(out)
      } else
        None
    }

    matchesGte(scalaVersion, gt).orElse(fromStartWith)
  }

  private def matchesGte[A](
      version: String,
      gtCases: Map[String, A],
  ): Option[A] = {
    val parsed = SemVer.Version.fromString(version)
    val less = gtCases
      .map { case (v, a) =>
        (SemVer.Version.fromString(v.stripPrefix(">=")), a)
      }
      .filter { case (v, _) => parsed >= v }

    less.toList.sortWith(_._1 < _._1).lastOption.map(_._2)
  }

}
