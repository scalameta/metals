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
class V5LockFileParser private[bazel] (
    artifacts: JsonObject,
    repositories: Option[JsonObject],
) extends MavenLockFileParser {

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
              .orElse(download(coordKey, info, groupId, artifactId, v, "jar"))

          jarPath.map { jar =>
            val sourcesPath =
              findSourcesPath(
                groupId,
                artifactId,
                v,
                repositoryNames,
                extDirs,
              ).orElse(
                download(coordKey, info, groupId, artifactId, v, "sources")
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

  private def download(
      coordinate: String,
      info: JsonObject,
      groupId: String,
      artifactId: String,
      version: String,
      classifier: String,
  ): Option[String] = {
    val checksum = for {
      shasums <- Option(info.getAsJsonObject("shasums"))
      value <- Option(shasums.get(classifier)).filter(_.isJsonPrimitive)
    } yield value.getAsString
    val key =
      if (classifier == "jar") coordinate else s"$coordinate:jar:$classifier"
    val suffix = if (classifier == "jar") "" else s"-$classifier"
    val relative =
      s"${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version$suffix.jar"
    val urls = repositories.toSeq.flatMap(_.entrySet().asScala).collect {
      case entry
          if entry.getValue.isJsonArray && entry.getValue.getAsJsonArray.asScala
            .exists(value =>
              value.isJsonPrimitive && value.getAsString == key
            ) =>
        s"${entry.getKey.stripSuffix("/")}/$relative"
    }
    urls.foldLeft(Option.empty[String]) { (found, url) =>
      found.orElse(MavenArtifactDownload.download(url, relative, checksum))
    }
  }

}
