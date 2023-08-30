import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.18"
  val scala213 = "2.13.11"
  val scala3 = "3.3.0"
  val firstScala3PCVersion = "3.3.2-RC1-bin-20230721-492f777-NIGHTLY"
  // When you can add to removedScalaVersions in MtagsResolver.scala with the last released version
  val scala3RC: Option[String] = Some("3.3.1-RC7")
  val sbtScala = "2.12.17"
  val ammonite212Version = "2.12.18"
  val ammonite213Version = "2.13.11"
  val ammonite3Version = "3.1.3"

  val ammonite = "2.5.9"
  val betterMonadicFor = "0.3.1"
  val bloop = "1.5.8"
  val bloopConfig = "1.5.5"
  val bsp = "2.1.0-M5"
  val coursier = "2.1.6"
  val coursierInterfaces =
    "1.0.18" // changing coursier interfaces version may be not binary compatible.
  // After each update of coursier interfaces, remember to bump the version in dotty repository.
  val debugAdapter = "3.1.4"
  val genyVersion = "1.0.0"
  val gradleBloop = "1.6.1"
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.9.5"
  val jsoup = "1.16.1"
  val kindProjector = "0.13.2"
  val lsp4jV = "0.20.1"
  val mavenBloop = "2.0.0"
  val mill = "0.11.2"
  val mdoc = "2.3.7"
  val munit = "1.0.0-M8"
  val pprint = "0.7.3"
  val sbtBloop = bloop
  val sbtJdiTools = "1.1.1"
  val scalaCli = "1.0.4"
  val scalafix = "0.11.0"
  val scalafmt = "3.7.12"
  val scalameta = "4.8.3"
  val scribe = "3.11.9"
  val semanticdb = scalameta
  val qdox = "2.0.3"

  val guava = "com.google.guava" % "guava" % "32.1.2-jre"
  val lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jV
  val dap4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jV

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
    "2.12.11",
    "2.12.12",
    "2.12.13",
    "2.12.14",
    "2.13.4",
    "2.13.5",
    "2.13.6",
    "2.13.7",
  )

  def nonDeprecatedScala2Versions = Seq(
    scala213,
    scala212,
    "2.12.17",
    "2.12.16",
    "2.12.15",
    "2.13.8",
    "2.13.9",
    "2.13.10",
  )

  def minimumSupportedSbtVersion = {
    // Update when deprecating a Scala version together with sbt version
    val sbtScalaVersion = "2.12.15"
    if (!nonDeprecatedScala2Versions.contains(sbtScalaVersion))
      throw new RuntimeException(
        "Please change minimalSupportedSbtVersion when removing support for a particular Scala version"
      )
    "1.6.0"
  }

  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // Scala 3
  def nonDeprecatedScala3Versions =
    Seq(scala3, "3.2.2", "3.1.3") ++ scala3RC.toSeq
  // ++ Seq("3.4.0-RC1-bin-SNAPSHOT") // local testing of scala3-presentation-compiler

  // whenever version is removed please add it to MtagsResolver under last supported Metals version
  def deprecatedScala3Versions =
    Seq("3.2.1", "3.2.0", "3.1.2", "3.1.1", "3.1.0")

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
