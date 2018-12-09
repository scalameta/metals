package docs

import java.nio.file.Paths
import scala.meta.internal.metals.{BuildInfo => V}

object Docs {
  lazy val snapshot = Snapshot.latest("snapshots")
  lazy val release = Snapshot.latest("releases")
  def releasesResolverTable: String = {
    <table>
      <thead>
        <th>Version</th>
        <th>Published</th>
        <th>Resolver</th>
      </thead>
      <tbody>
        <tr>
          <td>{release.version}</td>
          <td>{release.date}</td>
          <td><code>-r sonatype:releases</code></td>
        </tr>
        <tr>
          <td>{snapshot.version}</td>
          <td>{snapshot.date}</td>
          <td><code>-r sonatype:snapshots</code></td>
        </tr>
      </tbody>
    </table>
  }.toString

  def releasesTable: String = {
    <table>
      <thead>
        <th>Version</th>
        <th>Published</th>
      </thead>
      <tbody>
        <tr>
          <td>{release.version}</td>
          <td>{release.date}</td>
        </tr>
        <tr>
          <td>{snapshot.version}</td>
          <td>{snapshot.date}</td>
        </tr>
      </tbody>
    </table>
  }.toString

  lazy val stableVersion = V.metalsVersion.replaceFirst("\\+.*", "")
  def main(args: Array[String]): Unit = {
    val out = Paths.get("website", "target", "docs")
    val settings = mdoc
      .MainSettings()
      .withSiteVariables(
        Map[String, String](
          "VERSION" -> V.metalsVersion,
          "STABLE_VERSION" -> stableVersion,
          "SNAPSHOT_VERSION" -> snapshot.version,
          "SNAPSHOT_DATE" -> snapshot.lastModified.toString,
          "LOCAL_VERSION" -> V.localSnapshotVersion,
          "BLOOP_VERSION" -> V.bloopVersion,
          "SCALAMETA_VERSION" -> V.scalametaVersion,
          "SCALA211_VERSION" -> V.scala211,
          "SCALA_VERSION" -> V.scala212
        )
      )
      .withOut(out)
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if (exitCode != 0) {
      sys.exit(exitCode)
    }
  }
}
