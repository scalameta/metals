package docs

import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.{BuildInfo => V}

object Docs {
  lazy val snapshot: Snapshot = Snapshot.latest("snapshots", "2.13")
  lazy val release: Snapshot = Snapshot.latest("releases", "2.13")
  def releasesResolverTable: String = {
    <table>
      <thead>
        <tr>
          <th>Version</th>
          <th>Published</th>
          <th>Resolver</th>
        </tr>
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
        <tr>
          <th>Version</th>
          <th>Published</th>
        </tr>
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

  lazy val stableVersion: String = V.metalsVersion.replaceFirst("\\+.*", "")
  def main(args: Array[String]): Unit = {
    val target = Paths.get("website", "target")

    val dataOut = target.resolve("data")
    val docsOut = target.resolve("docs")

    Files.createDirectories(dataOut)
    Files.write(
      dataOut.resolve("latests.json"),
      s"""|{
          |  "release": "${release.version}",
          |  "snapshot": "${snapshot.version}"
          |}""".stripMargin.getBytes(),
    )

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
          "BLOOP_MAVEN_VERSION" -> V.mavenBloopVersion,
          "SBT_BLOOP_VERSION" -> V.sbtBloopVersion,
          "SCALAMETA_VERSION" -> V.scalametaVersion,
          "SCALA211_VERSION" -> V.scala211,
          "SCALA_VERSION" -> V.scala213,
        )
      )
      .withOut(docsOut)
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if (exitCode != 0) {
      sys.exit(exitCode)
    }
  }
}
