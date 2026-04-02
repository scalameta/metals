import scala.jdk.CollectionConverters._

object SemanticDbSupport {

  private val Scala211Versions = getVersions(2, 11, 12 to 12)
  private val last212 = V.scala212.split('.').last.toInt
  private val Scala212Versions = getVersions(2, 12, 9 to last212)
  private val last213 = V.scala213.split('.').last.toInt
  private val Scala213Versions = getVersions(2, 13, 1 to last213)

  private val AllScalaVersions =
    Scala213Versions ++ Scala212Versions ++ Scala211Versions

  private val latestVersion = Version
    .parse(V.scalameta)
    .getOrElse(sys.error("Failed to parse V.scalameta version"))

  // NOTE: When updating supported Scala versions, ensure V.scalameta is recent enough
  // to have semanticdb-scalac published for those versions. This is especially important
  // when SBT updates its meta-build Scala version (e.g., 2.12.21 in SBT 1.12.0).
  val last: Map[String, String] = {
    val result = AllScalaVersions.flatMap { scalaVersion =>
      val available = coursierapi.Complete
        .create()
        .withScalaVersion(scalaVersion)
        .withScalaBinaryVersion(scalaVersion.split('.').take(2).mkString("."))
        .withInput(s"org.scalameta:semanticdb-scalac_$scalaVersion:")
        .complete()
        .getCompletions()
        .asScala
        .toList

      available.reverse
        .collectFirst {
          case version if Version.parse(version).exists(latestVersion >= _) =>
            version
        }
        .orElse {
          // Fallback: if no version <= latestVersion exists, use the earliest available
          if (available.nonEmpty) {
            System.err.println(
              s"WARNING: No semanticdb-scalac version <= ${V.scalameta} found for Scala $scalaVersion. " +
                s"Using earliest available: ${available.head}. Consider updating V.scalameta."
            )
            Some(available.head)
          } else {
            System.err.println(
              s"ERROR: No semanticdb-scalac versions found for Scala $scalaVersion!"
            )
            None
          }
        }
        .map(scalaVersion -> _)
    }.toMap

    // Validate that all Scala versions have a mapping
    val missing = AllScalaVersions.filterNot(result.contains)
    if (missing.nonEmpty) {
      sys.error(
        s"Failed to find semanticdb-scalac versions for: ${missing.mkString(", ")}. " +
          s"This may happen if V.scalameta (${V.scalameta}) is too old."
      )
    }

    result
  }

  // returns versions from newest to oldest
  private def getVersions(major: Int, minor: Int, range: Range) = {
    val desc = if (range.step > 0) range.reverse else range
    desc.map { x => s"$major.$minor.$x" }
  }
}
