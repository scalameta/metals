import sbt._

object V {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.20"
  val scala213 = "2.13.16"
  val scala3 = "3.3.6"
  val scala3ForSBT2 = "3.6.4"
  val latestScala3Next = "3.7.2"

  // When you can add to removedScalaVersions in MtagsResolver.scala with the last released version
  val sbtScala = "2.12.18"
  val sbtMill = "2.13.15"
  // Rules need to be manually updated to support
  val bazelScalaVersion = "2.13.12"
  val betterMonadicFor = "0.3.1"
  val bloop = "2.0.12"
  val bloopConfig = "2.3.2"
  val bsp = "2.2.0-M2"
  val coursier = "2.1.24"
  // changing coursier interfaces version may be not binary compatible.
  // After each update of coursier interfaces, remember to bump the version in dotty repository.
  val coursierInterfaces = "1.0.28"
  val debugAdapter = "4.2.7"
  val genyVersion = "1.0.0"
  val gitter8Version = "0.17.0"
  val gradleBloop = "1.6.3"
  val java8Compat = "1.0.2"
  val javaSemanticdb = "0.11.0"
  val jsoup = "1.21.1"
  val kindProjector = "0.13.3"
  val lsp4jV = "0.24.0"
  val mavenBloop = "2.0.1"
  val mill = "1.0.2"
  val mdoc = "2.7.2"
  val munit = "1.1.1"
  val pprint = "0.7.3"
  val sbtBloop = bloop
  val sbtJdiTools = "1.2.0"
  val scalaCli = "1.8.4"
  val scalafix = "0.14.3"
  val scalafmt = "3.7.15"
  val scalameta = "4.13.8"
  val scribe = "3.17.0"
  val qdox = "2.2.0"

  val guava = "com.google.guava" % "guava" % "33.4.8-jre"
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
    "3" :: supportedScalaVersions.iterator
      .map(CrossVersion.partialVersion)
      .collect { case Some((a, b)) => s"$a.$b" }
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
    "2.13.13",
    "2.13.14",
    "2.13.15",
  )

  def minimumSupportedSbtVersion = {
    // Update when deprecating a Scala version together with sbt version
    val sbtScalaVersion = "2.12.17"
    if (!nonDeprecatedScala2Versions.contains(sbtScalaVersion))
      throw new RuntimeException(
        "Please change minimalSupportedSbtVersion when removing support for a particular Scala version"
      )
    "1.8.0"
  }

  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  def supportedScalaVersions = scala2Versions
  def nonDeprecatedScalaVersions = nonDeprecatedScala2Versions
  def deprecatedScalaVersions = deprecatedScala2Versions

  val quickPublishScalaVersions = Set(
    bazelScalaVersion,
    scala211,
    sbtScala,
    scala212,
    scala213,
    sbtMill,
  ).toList
}
