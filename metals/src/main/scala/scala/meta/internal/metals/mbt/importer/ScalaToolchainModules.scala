package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.util.Try
import scala.util.Using

import scala.meta.internal.metals.MetalsEnrichments._

object ScalaToolchainModules {

  // Anchored to the start of the line so it doesn't also match the sibling
  // `SCALA_VERSIONS=[...]` / `SCALA_MAJOR_VERSION=...` entries.
  private val scalaConfigVersionPattern =
    """(?m)^\s*SCALA_VERSION\s*=\s*['"]([^'"]+)['"]""".r

  /**
   * The default Scala version rules_scala resolved for the workspace, read
   * from the generated `@…rules_scala_config` repository's `config.bzl`.
   * `None` when rules_scala is not in use. The highest version wins
   * across stale config repos.
   */
  def scalaConfigVersion(externalDir: Path): Option[String] =
    BazelScalaVersions.maxVersion(
      listDirectory(externalDir)
        .filter(dir =>
          dir.getFileName.toString.endsWith("rules_scala_config") &&
            Files.isDirectory(dir)
        )
        .flatMap { dir =>
          val configBzl = dir.resolve("config.bzl")
          if (Files.isRegularFile(configBzl))
            Try(new String(Files.readAllBytes(configBzl))).toOption
              .flatMap(scalaConfigVersionPattern.findFirstMatchIn)
              .map(_.group(1))
          else None
        }
    )

  /**
   * Entries of `dir`, or an empty list if it is not a directory or cannot be
   * listed. IO errors (e.g. a repo being materialized concurrently) are
   * logged rather than propagated, so toolchain discovery never fails the
   * whole import.
   */
  private def listDirectory(dir: Path): List[Path] =
    if (Files.isDirectory(dir))
      Using(Files.list(dir))(_.iterator().asScala.toList).fold(
        error => {
          scribe.warn(s"bazel-mbt: could not list $dir: ${error.getMessage}")
          Nil
        },
        identity,
      )
    else Nil

}
