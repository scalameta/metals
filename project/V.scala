import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.17"
  val scala213 = "2.13.10"
  val scala3 = "3.2.1"
  val nextScala3RC = "3.2.2-RC2"
  val sbtScala = "2.12.16"
  val ammonite212Version = "2.12.17"
  val ammonite213Version = "2.13.10"
  val ammonite3Version = "3.1.3"

  val ammonite = "2.5.5"
  val betterMonadicFor = "0.3.1"
  val bloop = "1.5.6"
  val bloopConfig = "1.5.5"
  val bsp = "2.1.0-M3"
  val coursier = "2.1.0-RC4"
  val coursierInterfaces = "1.0.12"
  val debugAdapter = "3.0.4"
  val genyVersion = "0.7.1"
  val gradleBloop = "1.5.8"
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.7.4"
  val jsoup = "1.15.3"
  val kindProjector = "0.13.2"
  val lsp4jV = "0.19.0"
  val mavenBloop = "2.0.0"
  val mill = "0.10.10"
  val mdoc = "2.3.6"
  val munit = "1.0.0-M7"
  val organizeImportRule = "0.6.0"
  val pprint = "0.7.3"
  val sbtBloop = bloop
  val sbtJdiTools = "1.1.1"
  val scalaCli = "0.1.19"
  val scalafix = "0.10.4"
  val scalafmt = "3.5.9"
  val scalameta = "4.7.1"
  val scribe = "3.10.5"
  val semanticdb = scalameta
  val qdox = "2.0.3"

  val guava = "com.google.guava" % "guava" % "31.1-jre"
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
  def deprecatedScala2Versions = Seq(
    scala211,
    "2.12.10",
    "2.12.11",
    "2.12.12",
    "2.12.13",
    "2.13.3",
    "2.13.4",
    "2.13.5",
    "2.13.6",
  )

  def nonDeprecatedScala2Versions = Seq(
    scala213,
    scala212,
    "2.12.16",
    "2.12.15",
    "2.12.14",
    "2.13.7",
    "2.13.8",
    "2.13.9",
  )
  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // The minimum sbt version that uses a non-deprecated Scala version.
  // Currently uses Scala 2.12.12 - update upon deprecation.
  def minimumSupportedSbtVersion = "1.4.0"

  // Scala 3
  def nonDeprecatedScala3Versions =
    Seq(nextScala3RC, scala3, "3.2.0", "3.1.3")
  def deprecatedScala3Versions =
    Seq("3.2.2-RC1", "3.1.2", "3.1.1", "3.1.0", "3.0.2")
  def scala3Versions = nonDeprecatedScala3Versions ++ deprecatedScala3Versions

  lazy val nightlyScala3DottyVersions = {
    if (isNightliesEnabled)
      Scala3NightlyVersions.nightlyReleasesAfter(scala3)
    else
      Nil
  }

  def nightlyScala3Versions = nightlyScala3DottyVersions.map(_.toString)

  def supportedScalaVersions = scala2Versions ++ scala3Versions
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
      nextScala3RC,
    ).toList
}
