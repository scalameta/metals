import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.15"
  val scala213 = "2.13.8"
  val scala3 = "3.1.1"
  val nextScala3RC = "3.1.2-RC3"
  val sbtScala = "2.12.14"
  val ammonite212Version = scala212
  val ammonite213Version = "2.13.7"

  val ammonite = "2.5.2"
  val bloop = "1.4.13-50-8332ee9c"
  val bloopNightly = bloop
  val bsp = "2.0.0+70-f6e47d42-SNAPSHOT"
  val coursier = "2.1.0-M5"
  val coursierInterfaces = "1.0.6"
  val debugAdapter = "2.1.0"
  val genyVersion = "0.7.1"
  val gradleBloop = bloop
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.7.4"
  val jsoup = "1.14.3"
  val lsp4jV = "0.12.0"
  val mavenBloop = bloop
  val mill = "0.10.1"
  val mdoc = "2.3.1"
  val munit = "0.7.29"
  val organizeImportRule = "0.6.0"
  val pprint = "0.7.2"
  val sbtBloop = bloop
  val sbtJdiTools = "1.1.1"
  val scalafix = "0.9.34"
  val scalafmt = "3.4.0"
  val scalameta = "4.5.0"
  val scribe = "3.8.2"
  val semanticdb = scalameta
  val qdox = "2.0.1"

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
    "2.12.8",
    "2.12.9",
    "2.12.10",
    "2.13.1",
    "2.13.2",
    "2.13.3",
    "2.13.4"
  )

  def nonDeprecatedScala2Versions = Seq(
    scala213,
    scala212,
    "2.12.14",
    "2.12.13",
    "2.12.12",
    "2.12.11",
    "2.13.5",
    "2.13.6",
    "2.13.7"
  )
  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // Scala 3
  def nonDeprecatedScala3Versions = Seq(nextScala3RC, scala3, "3.1.0", "3.0.2")
  def deprecatedScala3Versions = Seq("3.1.2-RC2", "3.0.1", "3.0.0")
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
      scala3
    ).toList
}
