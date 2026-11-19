package scala.meta.internal.metals.mbt.importer.bazel

import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.mbt.MbtDependencyModule
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
      extDirs: Seq[ScannedExtDir],
      extensionPrefix: Option[String],
  ): Seq[MbtDependencyModule] = {
    val results =
      for {
        entry <- artifacts.entrySet().asScala.toSeq
        if entry.getValue.isJsonObject
        result <- parseArtifact(
          entry.getKey,
          entry.getValue,
          repositoryNames,
          extDirs,
          extensionPrefix,
        )
      } yield result

    val (missingIds, modules) = results.partitionMap(identity)
    logMissingJars(missingIds)
    modules
  }

  private def parseArtifact(
      coordKey: String,
      artifactInfo: JsonElement,
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
      extensionPrefix: Option[String],
  ): Option[Either[String, MbtDependencyModule]] = {
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
            findJarPath(
              groupId,
              artifactId,
              v,
              repositoryNames,
              extDirs,
              extensionPrefix,
            )

          jarPath match {
            case Some(jar) =>
              val sourcesPath =
                findSourcesPath(
                  groupId,
                  artifactId,
                  v,
                  repositoryNames,
                  extDirs,
                  extensionPrefix,
                )

              Some(
                Right(
                  MbtDependencyModule(
                    id = id,
                    jar = jar,
                    sources = sourcesPath.orNull,
                  )
                )
              )
            case None =>
              scribe.debug(s"bazel-mbt: JAR not found locally for $id")
              Some(Left(id))
          }
        }
      }
    }
  }

}
