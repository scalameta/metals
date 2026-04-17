package scala.meta.internal.metals.mbt.importer

import scala.concurrent.Future

import scala.meta.io.AbsolutePath

/**
 * Trait for build tools that can generate [[scala.meta.internal.metals.mbt.MbtBuild]] data.
 *
 * Each importer targets a specific build system (Maven, Gradle, user script, …)
 * and is responsible for writing its result to [[outputPath]].  The
 * [[MbtImport]] lifecycle manager reads all per-importer files after they
 * finish, merges them in memory, and writes the canonical `.metals/mbt.json`.
 *
 * For external scripts Metals sets two inputs before invoking the process:
 *  - env `MBT_OUTPUT_FILE` — absolute path the script must write JSON to
 *  - env `MBT_WORKSPACE`   — absolute path of the workspace root
 */
trait MbtImportProvider {

  /** Short identifier for this importer, e.g. `"maven"` or `"gradle"`. */
  def name: String

  /**
   * Run the importer and write its build JSON to [[outputPath]].
   * The returned [[Future]] completes when the file has been written.
   */
  def extract(workspace: AbsolutePath): Future[Unit]

  /**
   * Where this importer writes its result.
   * Convention: `.metals/mbt-<name>.json`.
   */
  def outputPath(workspace: AbsolutePath): AbsolutePath =
    workspace.resolve(s".metals/mbt-$name.json")

  /**
   * Returns `true` when the given path is a build file that, if modified,
   * should trigger a re-import (e.g. `pom.xml` for Maven).
   */
  def isBuildRelated(workspace: AbsolutePath, path: AbsolutePath): Boolean

  /**
   * Stable digest of all build files owned by this importer.
   * Used to skip redundant imports when nothing has changed.
   */
  def digest(workspace: AbsolutePath): Option[String]

  /** Root directory of the build. */
  def projectRoot: AbsolutePath
}
