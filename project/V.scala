import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.19"
  val scala213 = "2.13.14"
  val scala3 = "3.3.3"
  val firstScala3PCVersion = "3.3.4"
  val wrapperMetalsVersion = "3.4.0-RC1-bin-20231127-41e7d95-NIGHTLY"

  // When you can add to removedScalaVersions in MtagsResolver.scala with the last released version
  val scala3RC: Option[String] = None
  val sbtScala = "2.12.18"
  val ammonite212Version = "2.12.19"
  val ammonite213Version = "2.13.13"
  // Rules need to be manually updated to support
  val bazelScalaVersion = "2.13.12"
  val ammonite3Version = "3.3.3"

  val ammonite = "3.0.0-M1-8-35694880"
  val betterMonadicFor = "0.3.1"
  val bloop = "1.5.17"
  val bloopConfig = "1.5.5"
  val bsp = "2.1.1"
  val coursier = "2.1.9"
  val coursierInterfaces =
    "1.0.19" // changing coursier interfaces version may be not binary compatible.
  // After each update of coursier interfaces, remember to bump the version in dotty repository.
  val debugAdapter = "4.0.2"
  val genyVersion = "1.0.0"
  val gitter8Version = "0.16.2"
  val gradleBloop = "1.6.2"
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.9.9"
  val jsoup = "1.17.2"
  val kindProjector = "0.13.3"
  val lsp4jV = "0.20.1"
  val mavenBloop = "2.0.0"
  val mill = "0.11.7"
  val mdoc = "2.5.2"
  val munit = "1.0.0-M11"
  val pprint = "0.7.3"
  val sbtBloop = bloop
  val sbtJdiTools = "1.1.1"
  val scalaCli = "1.2.0"
  val scalafix = "0.12.0"
  val scalafmt = "3.7.15"
  val scalameta = "4.9.3"
  val scribe = "3.13.0"
  val qdox = "2.1.0"

  val guava = "com.google.guava" % "guava" % "33.0.0-jre"
  val lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jV
  val dap4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jV

  def semanticdb(scalaVersion: String) =
    SemanticDbSupport.last.getOrElse(scalaVersion, scalameta)

  def isNightliesEnabled: Boolean =
    sys.env.get("CI").isDefined || sys.env.get("NIGHTLIES").isDefined

  // List of supported Scala versions in SemanticDB. Needs to be manually updated
  // for every SemanticDB upgrade.
  def supportedScalaBinaryVersions =
    supportedScalaVersions.iterator
      .map(CrossVersion.partialVersion)
      .collect {
        case Some((3, _)) => "3"
        case Some((a, b)) => s"$a.$b"
      }
      .toList
      .distinct

  // Scala 2
  // whenever version is removed please add it to MtagsResolver under last supported Metals version
  def deprecatedScala2Versions = Seq(
    scala211,
    "2.12.12",
    "2.12.13",
    "2.12.14",
    "2.12.15",
    "2.13.7",
    "2.13.8",
    "2.13.9",
    "2.13.10",
  )

  def nonDeprecatedScala2Versions = Seq(
    scala213,
    scala212,
    "2.12.18",
    "2.12.17",
    "2.12.16",
    "2.13.11",
    "2.13.12",
    "2.13.13",
  )

  def minimumSupportedSbtVersion = {
    // Update when deprecating a Scala version together with sbt version
    val sbtScalaVersion = "2.12.16"
    if (!nonDeprecatedScala2Versions.contains(sbtScalaVersion))
      throw new RuntimeException(
        "Please change minimalSupportedSbtVersion when removing support for a particular Scala version"
      )
    "1.7.0"
  }

  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // Scala 3
  def nonDeprecatedScala3Versions =
    Seq(scala3, "3.3.1", "3.2.2", "3.1.3") ++ scala3RC.toSeq

  // whenever version is removed please add it to MtagsResolver under last supported Metals version
  def deprecatedScala3Versions =
    Seq("3.3.2", "3.2.1", "3.2.0", "3.1.2", "3.1.1", "3.1.0")

  // NOTE if you had a new Scala Version make sure it's contained in quickPublishScalaVersions
  def scala3Versions = nonDeprecatedScala3Versions ++ deprecatedScala3Versions

  lazy val nightlyScala3DottyVersions = {
    if (isNightliesEnabled)
      Scala3NightlyVersions.nightlyReleasesAfter(firstScala3PCVersion)
    else
      Nil
  }

  def nightlyScala3Versions = nightlyScala3DottyVersions.map(_.toString)

  def supportedScalaVersions =
    scala2Versions ++ scala3Versions
  def nonDeprecatedScalaVersions =
    nonDeprecatedScala2Versions ++ nonDeprecatedScala3Versions
  def deprecatedScalaVersions =
    deprecatedScala2Versions ++ deprecatedScala3Versions

  val quickPublishScalaVersions =
    Set(
      scala211,
      sbtScala,
      scala212,
      ammonite212Version,
      scala213,
      ammonite213Version,
      scala3,
      ammonite3Version,
    ).toList ++ scala3RC.toList
}
