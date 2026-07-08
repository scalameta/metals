package scala.meta.internal.metals.mbt.importer.bazel

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.ScannedExtDir

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * New format (rules_jvm_external v5+):
 * <pre>
 * {
 *   "artifacts": {
 *     "com.google.guava:guava": {
 *       "version": "31.1-jre",
 *       "shasums": { "jar": "...", "sources": "..." }
 *     }
 *   }
 * }
 * </pre>
 */
class V5LockFileParser private[bazel] (artifacts: JsonObject)
    extends MavenLockFileParser {

  override def parse(
      repositoryNames: Seq[String],
      extDirs: Seq[BazelMavenJsonImporter.ScannedExtDir],
  ): Seq[MbtDependencyModule] = {
    artifacts.entrySet().asScala.toSeq.flatMap { entry =>
      val coordKey = entry.getKey // e.g., "com.google.guava:guava"
      val artifactInfo = entry.getValue
      if (!artifactInfo.isJsonObject) None
      else
        parseArtifact(
          coordKey,
          artifactInfo,
          repositoryNames,
          extDirs,
        )
    }
  }

  private def parseArtifact(
      coordKey: String,
      artifactInfo: JsonElement,
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Option[MbtDependencyModule] = {
    val info = artifactInfo.getAsJsonObject

    // Get version
    val version = Option(info.get("version"))
      .filter(_.isJsonPrimitive)
      .map(_.getAsString)

    version.flatMap { v =>
      if (v.contains("SNAPSHOT")) {
        scribe.debug(s"Skipping SNAPSHOT: $coordKey:$v")
        None
      } else {
        val parts = coordKey.split(":")
        if (parts.length != 2) None
        else {
          val (groupId, artifactId) = (parts(0), parts(1))
          val id = s"$groupId:$artifactId:$v"

          // Find JAR file path
          val jarPath =
            findJarPath(groupId, artifactId, v, repositoryNames, extDirs)

          jarPath.map { jar =>
            val sourcesPath =
              findSourcesPath(
                groupId,
                artifactId,
                v,
                repositoryNames,
                extDirs,
              )

            MbtDependencyModule(
              id = id,
              jar = jar,
              sources = sourcesPath.orNull,
            )
          }
        }
      }
    }
  }

}
