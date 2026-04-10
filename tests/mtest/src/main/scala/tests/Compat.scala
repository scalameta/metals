package tests

import scala.meta.internal.semver.SemVer

object Compat {

  /**
   * Cases might be described as:
   *  - Starts with: "3.0.1" -> value OR "3.0" -> value OR "3" -> value
   *  - Greater or equal: ">=3.0.0" -> value OR ">=2.16.17&&2" -> value
   */
  def forScalaVersion[A](
      scalaVersion: String,
      cases: Map[String, A]
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
      gtCases: Map[String, A]
  ): Option[A] = {
    val parsed = SemVer.Version.fromString(version)
    val less = gtCases
      .map { case (v0, a) =>
        val (v, additionalConditions) =
          if (v0.contains("&&")) {
            val allConditions = v0.split("&&")
            allConditions.head -> allConditions.tail
          } else v0 -> Array.empty[String]
        (
          SemVer.Version.fromString(v.stripPrefix(">=")),
          additionalConditions,
          a
        )
      }
      .filter { case (v, additionalConditions, _) =>
        parsed >= v && additionalConditions.forall(version.startsWith)
      }

    less.toList.sortWith(_._1 < _._1).lastOption.map(_._3)
  }

}
