package tests

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.{util => ju}

import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.FallbackClasspaths
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.io.AbsolutePath

/**
 * A cross-compiled workspace (e.g. rules_scala: 2.11/2.12/2.13/3) contributes
 * `scala-library` for every Scala version to the MBT dependency-module list.
 * The fallback presentation compiler is created per Scala version, so its
 * classpath must carry only the matching version's Scala jars — mixing
 * several `scala-library` versions (or a Scala 3 compiler) onto one 2.x
 * compiler corrupts its symbol table (`FatalError: EmptyScope.enter`).
 */
class FallbackClasspathsMbtSuite extends BaseSuite {

  private def mod(id: String, jar: String): MbtDependencyModule =
    MbtDependencyModule(
      id = id,
      jar = Paths.get(s"/tmp/$jar").toUri.toString,
      sources = null,
    )

  // A representative cross-compiled module set: scala-library for four Scala
  // versions, the 2.13 + Scala 3 compilers, version-suffixed libraries, and two
  // version-agnostic Java jars.
  private val modules: Seq[MbtDependencyModule] = Seq(
    mod("org.scala-lang:scala-library:2.13.18", "scala-library-2.13.18.jar"),
    mod("org.scala-lang:scala-compiler:2.13.18", "scala-compiler-2.13.18.jar"),
    mod("org.scala-lang:scala-reflect:2.13.18", "scala-reflect-2.13.18.jar"),
    mod("org.scala-lang:scala-library:2.12.21", "scala-library-2.12.21.jar"),
    mod("org.scala-lang:scala-library:2.11.12", "scala-library-2.11.12.jar"),
    mod("org.scala-lang:scala3-library_3:3.8.3", "scala3-library_3-3.8.3.jar"),
    mod(
      "org.scala-lang:scala3-compiler_3:3.8.3",
      "scala3-compiler_3-3.8.3.jar",
    ),
    mod(
      "org.scala-lang.modules:scala-xml_2.12:2.3.0",
      "scala-xml_2.12-2.3.0.jar",
    ),
    mod("org.scalatest:scalatest_2.12:3.2.19", "scalatest_2.12-3.2.19.jar"),
    mod(
      "io.github.java-diff-utils:java-diff-utils:4.16",
      "java-diff-utils-4.16.jar",
    ),
    mod("com.google.guava:guava:30.0-jre", "guava-30.0-jre.jar"),
  )

  private def fallback: FallbackClasspaths = {
    val list = new ju.ArrayList[MbtDependencyModule]()
    modules.foreach(list.add)
    val build = MbtBuild(dependencyModules = list, namespaces = null)
    new FallbackClasspaths(
      workspace = AbsolutePath(Files.createTempDirectory("fallback-cp")),
      fallbackClasspathsConfig = () => FallbackClasspathConfig.mbt,
      mbtBuild = () => build,
    )
  }

  private def jarNames(paths: Seq[Path]): Set[String] =
    paths.map(_.getFileName.toString).toSet

  test("scala-2.13-excludes-other-scala-versions") {
    assertEquals(
      jarNames(fallback.scalaCompilerClasspath("2.13.18")),
      Set(
        "scala-library-2.13.18.jar", "scala-compiler-2.13.18.jar",
        "scala-reflect-2.13.18.jar",
        // version-agnostic Java jars are always included
        "java-diff-utils-4.16.jar", "guava-30.0-jre.jar",
      ),
    )
  }

  test("scala-3-keeps-2.13-stdlib-excludes-2.11-2.12") {
    // A Scala 3 compiler runs on the Scala 2.13 standard library, so the 2.13
    // `scala-library` (and 2.13 artifacts reused via 3/2.13 forward
    // compatibility) must stay; only the 2.11/2.12 jars are dropped. Without
    // the 2.13 stdlib the Scala 3 presentation compiler has no standard
    // library and types nothing (navigation comes back empty).
    assertEquals(
      jarNames(fallback.scalaCompilerClasspath("3.8.3")),
      Set(
        "scala3-library_3-3.8.3.jar", "scala3-compiler_3-3.8.3.jar",
        "scala-library-2.13.18.jar", "scala-compiler-2.13.18.jar",
        "scala-reflect-2.13.18.jar", "java-diff-utils-4.16.jar",
        "guava-30.0-jre.jar",
      ),
    )
  }

  test("scala-2.12-matches-by-name-suffix") {
    assertEquals(
      jarNames(fallback.scalaCompilerClasspath("2.12.21")),
      Set(
        "scala-library-2.12.21.jar",
        // recognised by the `_2.12` name suffix even though the org is not
        // `org.scala-lang`
        "scala-xml_2.12-2.3.0.jar", "scalatest_2.12-3.2.19.jar",
        "java-diff-utils-4.16.jar", "guava-30.0-jre.jar",
      ),
    )
  }
}
