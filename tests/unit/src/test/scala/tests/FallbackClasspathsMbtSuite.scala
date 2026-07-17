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
 * Mixing several `scala-library` versions (or a Scala 3 compiler) onto one
 * 2.x compiler corrupts its symbol table (`FatalError: EmptyScope.enter`).
 */
class FallbackClasspathsMbtSuite extends BaseSuite {

  private def mod(id: String, jar: String): MbtDependencyModule =
    MbtDependencyModule(
      id = id,
      jar = Paths.get(s"/tmp/$jar").toUri.toString,
      sources = null,
    )

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

  private def fallbackOf(mods: Seq[MbtDependencyModule]): FallbackClasspaths = {
    val list = new ju.ArrayList[MbtDependencyModule]()
    mods.foreach(list.add)
    val build =
      MbtBuild(
        dependencyModules = list,
        namespaces = null,
        uncheckedSources = null,
      )
    new FallbackClasspaths(
      workspace = AbsolutePath(Files.createTempDirectory("fallback-cp")),
      fallbackClasspathsConfig = () => FallbackClasspathConfig.mbt,
      mbtBuild = () => build,
    )
  }

  private def fallback: FallbackClasspaths = fallbackOf(modules)

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
    // 2.13 stays via Scala 3/2.13 forward compatibility.
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

  test("java-fallback-includes-all-scala-binary-versions-deduped") {
    // The Java fallback compiler has no Scala symbol table, so MBT jars are
    // not version-filtered, but each artifact keeps a single copy (preferring
    // the fallback binary version, then the highest version).
    assertEquals(
      jarNames(fallback.javaCompilerClasspath()),
      Set(
        "scala-library-2.13.18.jar", "scala-compiler-2.13.18.jar",
        "scala-reflect-2.13.18.jar", "scala3-library_3-3.8.3.jar",
        "scala3-compiler_3-3.8.3.jar", "scala-xml_2.12-2.3.0.jar",
        "scalatest_2.12-3.2.19.jar", "java-diff-utils-4.16.jar",
        "guava-30.0-jre.jar",
      ),
    )
  }

  test("java-fallback-dedups-multiple-versions-of-one-artifact") {
    // A stale scala3-compiler on the javac classpath shadows the newer one:
    // javac binds classes from the first jar that has them, so symbols added
    // in later compiler versions come back as "cannot find symbol".
    val cp = fallbackOf(
      Seq(
        mod(
          "org.scala-lang:scala3-compiler_3:3.1.3",
          "scala3-compiler_3-3.1.3.jar",
        ),
        mod(
          "org.scala-lang:scala3-compiler_3:3.6.4",
          "scala3-compiler_3-3.6.4.jar",
        ),
        mod(
          "org.scala-lang:scala3-compiler_3:3.8.4",
          "scala3-compiler_3-3.8.4.jar",
        ),
        mod("com.google.guava:guava:30.0-jre", "guava-30.0-jre.jar"),
      )
    )
    assertEquals(
      jarNames(cp.javaCompilerClasspath()),
      Set("scala3-compiler_3-3.8.4.jar", "guava-30.0-jre.jar"),
    )
  }

  test("scala-3-dedups-cross-binary-version-copies-of-one-library") {
    // A library cross-published as both `_3` and `_2.13`.
    val cp = fallbackOf(
      Seq(
        mod(
          "org.scala-lang:scala3-library_3:3.8.3",
          "scala3-library_3-3.8.3.jar",
        ),
        mod(
          "org.scala-lang:scala-library:2.13.18",
          "scala-library-2.13.18.jar",
        ),
        mod("org.typelevel:cats-core_3:2.10.0", "cats-core_3-2.10.0.jar"),
        mod("org.typelevel:cats-core_2.13:2.9.0", "cats-core_2.13-2.9.0.jar"),
      )
    )
    assertEquals(
      jarNames(cp.scalaCompilerClasspath("3.8.3")),
      Set(
        "scala3-library_3-3.8.3.jar",
        "scala-library-2.13.18.jar",
        "cats-core_3-2.10.0.jar",
      ),
    )
  }

  test("dedups-multiple-versions-of-one-library-keeping-highest") {
    val cp = fallbackOf(
      Seq(
        mod(
          "org.scala-lang:scala-library:2.13.18",
          "scala-library-2.13.18.jar",
        ),
        mod("com.example:lib:1.0.0", "lib-1.0.0.jar"),
        mod("com.example:lib:2.0.0", "lib-2.0.0.jar"),
      )
    )
    assertEquals(
      jarNames(cp.scalaCompilerClasspath("2.13.18")),
      Set("scala-library-2.13.18.jar", "lib-2.0.0.jar"),
    )
  }
}
