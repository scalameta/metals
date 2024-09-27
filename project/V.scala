import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.20"
  val scala213 = "2.13.15"
  val scala3 = "3.3.3"

  // When you can add to removedScalaVersions in MtagsResolver.scala with the last released version
  val scala3RC: Option[String] = None
  val sbtScala = "2.12.18"
  val ammonite212Version = "2.12.19"
  val ammonite213Version = "2.13.14"
  // Rules need to be manually updated to support
  val bazelScalaVersion = "2.13.12"
  val ammonite3Version = "3.3.3"

  val ammonite = "3.0.0-M2-30-486378af"
  val betterMonadicFor = "0.3.1"
  val bloop = "2.0.2"
  val bloopConfig = "2.0.3"
  val bsp = "2.2.0-M2"
  val coursier = "2.1.13"
  // changing coursier interfaces version may be not binary compatible.
  // After each update of coursier interfaces, remember to bump the version in dotty repository.
  val coursierInterfaces = "1.0.21"
  val debugAdapter = "4.2.1"
  val genyVersion = "1.0.0"
  val gitter8Version = "0.16.2"
  val gradleBloop = "1.6.2"
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.10.3"
  val jsoup = "1.18.1"
  val kindProjector = "0.13.3"
  val lsp4jV = "0.23.1"
  val mavenBloop = "2.0.1"
  val mill = "0.11.9"
  val mdoc = "2.6.1"
  val munit = "1.0.2"
  val pprint = "0.7.3"
  val sbtBloop = bloop
  val sbtJdiTools = "1.1.1"
  val scalaCli = "1.5.0"
  val scalafix = "0.12.1"
  val scalafmt = "3.7.15"
  val scalameta = "4.9.9"
  val scribe = "3.15.0"
  val qdox = "2.1.0"

  val guava = "com.google.guava" % "guava" % "33.3.1-jre"
  val lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jV
  val dap4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jV

  val eclipseJdt = Seq(
    "org.eclipse.jdt" % "org.eclipse.jdt.core" % "3.25.0" exclude ("*", "*"),
    "org.eclipse.platform" % "org.eclipse.ant.core" % "3.5.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.compare.core" % "3.6.600" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.commands" % "3.9.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.contenttype" % "3.7.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.expressions" % "3.6.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.filesystem" % "1.7.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.jobs" % "3.10.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.resources" % "3.13.500" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.runtime" % "3.16.0" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.core.variables" % "3.4.600" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.equinox.app" % "1.4.300" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.equinox.common" % "3.10.600" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.equinox.preferences" % "3.7.600" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.equinox.registry" % "3.8.600" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.osgi" % "3.15.0" exclude ("*", "*"),
    "org.eclipse.platform" % "org.eclipse.team.core" % "3.8.700" exclude (
      "*",
      "*",
    ),
    "org.eclipse.platform" % "org.eclipse.text" % "3.9.0" exclude ("*", "*"),
  )

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
    scala211
  )

  def nonDeprecatedScala2Versions = Seq(
    scala213,
    scala212,
    "2.12.19",
    "2.12.18",
    "2.12.17",
    "2.12.16",
    "2.13.11",
    "2.13.12",
    "2.13.13",
    "2.13.14",
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
    Seq(scala3, "3.3.1") ++ scala3RC.toSeq

  // whenever version is removed please add it to MtagsResolver under last supported Metals version
  def deprecatedScala3Versions =
    Seq(
      "3.3.2", // was a broken release
      "3.2.2",
    )

  // NOTE if you had a new Scala Version make sure it's contained in quickPublishScalaVersions
  def scala3Versions = nonDeprecatedScala3Versions ++ deprecatedScala3Versions

  def supportedScalaVersions =
    scala2Versions ++ scala3Versions
  def nonDeprecatedScalaVersions =
    nonDeprecatedScala2Versions ++ nonDeprecatedScala3Versions
  def deprecatedScalaVersions =
    deprecatedScala2Versions ++ deprecatedScala3Versions

  val quickPublishScalaVersions = Set(
    bazelScalaVersion,
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
