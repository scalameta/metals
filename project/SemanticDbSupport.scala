import scala.jdk.CollectionConverters._

object SemanticDbSupport {

  private val Scala211Versions = getVersions(2, 11, 12 to 12)
  private val last212 = V.scala212.split('.').last.toInt
  private val Scala212Versions = getVersions(2, 12, 9 to last212)
  private val last213 = V.scala213.split('.').last.toInt
  private val Scala213Versions = getVersions(2, 13, 1 to last213)

  private val AllScalaVersions =
    Scala213Versions ++ Scala212Versions ++ Scala211Versions

  val last: Map[String, String] = AllScalaVersions.flatMap { scalaVersion =>
    coursierapi.Complete
      .create()
      .withScalaVersion(scalaVersion)
      .withScalaBinaryVersion(scalaVersion.split('.').take(2).mkString("."))
      .withInput(s"org.scalameta:semanticdb-scalac_$scalaVersion:")
      .complete()
      .getCompletions()
      .asScala
      .lastOption
      .map(scalaVersion -> _)
  }.toMap

  // returns versions from newest to oldest
  private def getVersions(major: Int, minor: Int, range: Range) = {
    val desc = if (range.step > 0) range.reverse else range
    desc.map { x => s"$major.$minor.$x" }
  }
}
