import scala.jdk.CollectionConverters._

object Scala3NightlyVersions {

  val broken =
    Set(
      "3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY",
      "3.2.0-RC1-bin-20220308-29073f1-NIGHTLY",
      "3.1.3-RC1-bin-20220406-73cda0c-NIGHTLY",
    ).flatMap(Version.parse)

  /**
   * Fetches last 5 nightly releases.
   * They should come at least after the last supported scala3 version
   * otherwise there is no point to use these versions.
   */
  def nightlyReleasesAfter(version: String): List[Version] = {
    val lastVersion = Version.parse(version) match {
      case Some(v) => v
      case None =>
        throw new Exception(s"Can't parse dotty versions from $version")
    }

    try {
      fetchScala3NightlyVersions()
        .filter(_ > lastVersion)
        .sorted
        .takeRight(5)
    } catch {
      case e: Throwable =>
        println("Fetching Scala3 nigthly versions failed")
        e.printStackTrace()
        Nil
    }
  }

  def nonPublishedNightlyVersions(): List[Version] = {
    val all = fetchScala3NightlyVersions()
    lastPublishedMtagsForNightly() match {
      case None =>
        println("Error: Unable to find last nightly mtag")
        Nil
      case Some(last) =>
        all.filter(_ > last)
    }
  }

  private def fetchScala3NightlyVersions(): List[Version] = {
    coursierapi.Complete
      .create()
      .withInput("org.scala-lang:scala3-compiler_3:")
      .complete()
      .getCompletions()
      .asScala
      .filter(_.endsWith("NIGHTLY"))
      .flatMap(Version.parse)
      .filter(!broken.contains(_))
      .toList
  }

  private def lastPublishedMtagsForNightly(): Option[Version] = {
    coursierapi.Complete
      .create()
      .withInput("org.scalameta:mtags_3")
      .complete()
      .getCompletions()
      .asScala
      .filter(_.endsWith("NIGHTLY"))
      .map(_.stripPrefix("mtags_"))
      .flatMap(Version.parse)
      .sorted
      .lastOption
  }
}
