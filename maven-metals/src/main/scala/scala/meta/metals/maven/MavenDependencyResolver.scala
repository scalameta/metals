package scala.meta.metals.maven

import java.io.File
import java.{util => ju}

import scala.jdk.CollectionConverters._

import org.apache.maven.artifact.Artifact
import org.apache.maven.model.Dependency
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.ArtifactResult

private[maven] object MavenDependencyResolver {

  private[maven] val CompileScopes = Set(
    Artifact.SCOPE_COMPILE,
    Artifact.SCOPE_PROVIDED,
    Artifact.SCOPE_SYSTEM,
    null,
  )

  case class ArtifactKey(
      groupId: String,
      artifactId: String,
      version: String,
      classifier: Option[String],
      extension: String,
  )

  def annotationProcessorPathArgs(
      coords: List[(String, String, String)],
      artifactFiles: Map[ArtifactKey, File],
      localRepoBase: File,
      mojo: MbtMojo,
      log: Log,
  ): List[String] = {
    val jars = coords.flatMap { case (g, a, v) =>
      val key = ArtifactKey(g, a, v, None, "jar")
      artifactFiles
        .get(key)
        .orElse(localArtifactPath(localRepoBase, g, a, v, None, "jar"))
        .orElse(resolveDependencyJarsBatch(List(key), mojo, log).get(key))
    }
    if (jars.isEmpty) Nil
    else
      List(
        "-processorpath",
        jars.map(_.getAbsolutePath).mkString(File.pathSeparator),
      )
  }

  def externalCoords(
      projects: List[MavenProject],
      reactorCoords: Set[(String, String, String)],
  ): Set[(String, String, String)] =
    projects.flatMap { project =>
      val fromArtifacts = project.getArtifacts.asScala
        .map(a => (a.getGroupId, a.getArtifactId, a.getVersion))
        .filterNot(reactorCoords.contains)
        .filter(_._3 != null)
      val fromDeclared = project.getDependencies.asScala
        .map(d => (d.getGroupId, d.getArtifactId, resolveVersion(d, project)))
        .filterNot(reactorCoords.contains)
        .filter(_._3 != null)

      fromArtifacts ++ fromDeclared
    }.toSet

  def resolveDependencyJars(
      projects: List[MavenProject],
      reactorCoords: Set[(String, String, String)],
      localRepoBase: File,
      mojo: MbtMojo,
      log: Log,
      emit: String => Unit,
  ): Map[ArtifactKey, File] = {
    val keysFromArtifacts = projects
      .flatMap { project =>
        project.getArtifacts.asScala.flatMap { artifact =>
          val coords =
            (artifact.getGroupId, artifact.getArtifactId, artifact.getVersion)
          Option.when(!reactorCoords.contains(coords))(artifactKey(artifact))
        }
      }

    val keysFromDeclared = projects.flatMap { project =>
      project.getDependencies.asScala.flatMap { dep =>
        val key = dependencyKey(dep, project)
        val coords = key.map(k => (k.groupId, k.artifactId, k.version))
        key.filter(_ => !coords.exists(reactorCoords.contains))
      }
    }

    val keys = (keysFromArtifacts ++ keysFromDeclared)
      .filter(key => key.version != null && key.extension == "jar")
      .distinct

    val localFiles = keys.flatMap { key =>
      localArtifactPath(
        localRepoBase,
        key.groupId,
        key.artifactId,
        key.version,
        key.classifier,
        key.extension,
      ).map(key -> _)
    }.toMap

    val missing = keys.filterNot(localFiles.contains)
    if (missing.isEmpty) localFiles
    else {
      emit(
        s"[metals-maven] resolving ${missing.size} dependency JAR(s) missing from local cache..."
      )
      localFiles ++ resolveDependencyJarsBatch(missing, mojo, log)
    }
  }

  def collectDeps(
      project: MavenProject,
      isTest: Boolean,
      reactorByCoords: Map[(String, String, String), MavenProject],
      depModuleMap: ju.LinkedHashMap[String, DepModuleEntry],
      localRepoBase: File,
      artifactFiles: Map[ArtifactKey, File],
      sourcesCache: Map[String, File],
      log: Log,
  ): List[String] = {
    val depModuleIds = List.newBuilder[String]

    def registerExternal(
        g: String,
        a: String,
        v: String,
        classifier: String,
        fileOpt: Option[File],
    ): Unit =
      for {
        _ <- Option(v).filter(_.nonEmpty)
        file <- fileOpt
        id = artifactId(g, a, v, classifier)
      } {
        if (depModuleMap.containsKey(id)) {
          depModuleIds += id
        } else if (!file.exists()) {
          log.debug(s"metals-maven-plugin: $id not found, skipping")
        } else if (!isJar(file)) {
          log.debug(s"metals-maven-plugin: $id is not a JAR, skipping")
        } else {
          val sourcesJar = sourcesCache
            .get(s"$g:$a:$v")
            .orElse {
              val f = localSourcesJarPath(localRepoBase, g, a, v)
              if (f.isFile) Some(f) else None
            }
          depModuleMap.put(
            id,
            DepModuleEntry(
              id = id,
              jar = file.toURI.toString,
              sources = sourcesJar.map(_.toURI.toString).orNull,
            ),
          )
          depModuleIds += id
        }
      }

    val allArtifacts = project.getArtifacts.asScala

    if (allArtifacts.nonEmpty) {
      val scopeFilter: Artifact => Boolean =
        if (!isTest) a => CompileScopes.contains(a.getScope)
        else _ => true
      for (a <- allArtifacts if scopeFilter(a)) {
        val coords = (a.getGroupId, a.getArtifactId, a.getVersion)
        if (!reactorByCoords.contains(coords)) {
          val key = artifactKey(a)
          val file = Option(a.getFile)
            .filter(_.isFile)
            .orElse(artifactFiles.get(key))
          registerExternal(
            a.getGroupId,
            a.getArtifactId,
            a.getVersion,
            Option(a.getClassifier).filter(_.nonEmpty).orNull,
            file,
          )
        }
      }
    } else {
      for (dep <- project.getDependencies.asScala) {
        val inScope = isTest || CompileScopes.contains(dep.getScope)
        if (inScope) {
          val v = resolveVersion(dep, project)
          val coords = (dep.getGroupId, dep.getArtifactId, v)
          if (!reactorByCoords.contains(coords))
            registerExternal(
              dep.getGroupId,
              dep.getArtifactId,
              v,
              dependencyClassifier(dep).orNull,
              localArtifactPath(
                localRepoBase,
                dep.getGroupId,
                dep.getArtifactId,
                v,
                dependencyClassifier(dep),
                dependencyExtension(dep),
              ),
            )
        }
      }
    }

    depModuleIds.result()
  }

  def resolveLocalSourcesOnly(
      coords: Set[(String, String, String)],
      base: File,
  ): Map[String, File] =
    coords.flatMap { case (g, a, v) =>
      val f = localSourcesJarPath(base, g, a, v)
      if (f.isFile) Some(s"$g:$a:$v" -> f) else None
    }.toMap

  def resolveSourcesJarsBatch(
      coords: Set[(String, String, String)],
      base: File,
      mojo: MbtMojo,
      log: Log,
  ): Map[String, File] = {
    val remoteRepos =
      mojo.getSession.getCurrentProject.getRemoteProjectRepositories
    val fromLocal = resolveLocalSourcesOnly(coords, base)
    val missing = coords.filter { case (g, a, v) =>
      !fromLocal.contains(s"$g:$a:$v")
    }

    val requests = missing.toList.map { case (g, a, v) =>
      val req = new ArtifactRequest()
      req.setArtifact(new DefaultArtifact(g, a, "sources", "jar", v))
      req.setRepositories(remoteRepos)
      req
    }

    val fromRemote =
      if (requests.isEmpty) Map.empty[String, File]
      else
        resolveArtifacts(requests, mojo, log).map { case (art, file) =>
          s"${art.getGroupId}:${art.getArtifactId}:${art.getVersion}" -> file
        }

    fromLocal ++ fromRemote
  }

  private def resolveDependencyJarsBatch(
      keys: List[ArtifactKey],
      mojo: MbtMojo,
      log: Log,
  ): Map[ArtifactKey, File] = {
    val remoteRepos =
      mojo.getSession.getCurrentProject.getRemoteProjectRepositories
    val requests = keys.map { key =>
      val req = new ArtifactRequest()
      req.setArtifact(
        new DefaultArtifact(
          key.groupId,
          key.artifactId,
          key.classifier.orNull,
          key.extension,
          key.version,
        )
      )
      req.setRepositories(remoteRepos)
      req
    }

    resolveArtifacts(requests, mojo, log).map { case (art, file) =>
      artifactKey(art) -> file
    }
  }

  private def resolveArtifacts(
      requests: List[ArtifactRequest],
      mojo: MbtMojo,
      log: Log,
  ): Map[org.eclipse.aether.artifact.Artifact, File] =
    try {
      mojo.getRepoSystem
        .resolveArtifacts(mojo.getRepositorySession, requests.asJava)
        .asScala
        .flatMap(resolvedFile)
        .toMap
    } catch {
      case e: ArtifactResolutionException =>
        e.getResults.asScala.flatMap(resolvedFile).toMap
      case _: Exception =>
        log.debug("metals-maven-plugin: batch dependency resolution failed")
        Map.empty
    }

  private def resolvedFile(
      result: ArtifactResult
  ): Option[(org.eclipse.aether.artifact.Artifact, File)] =
    for {
      art <- Option(result.getArtifact)
      file <- Option(art.getFile).filter(_.isFile)
    } yield art -> file

  private def artifactId(
      g: String,
      a: String,
      v: String,
      classifier: String,
  ): String =
    Option(classifier).filter(_.nonEmpty).fold(s"$g:$a:$v")(c => s"$g:$a:$c:$v")

  private def resolveVersion(dep: Dependency, project: MavenProject): String =
    Option(dep.getVersion).filter(_.nonEmpty).getOrElse {
      val versionMap = Option(project.getManagedVersionMap)
      val classifier = Option(dep.getClassifier).filter(_.nonEmpty)
      val keys = List(
        classifier
          .map(c =>
            s"${dep.getGroupId}:${dep.getArtifactId}:${dep.getType}:$c"
          ),
        Some(s"${dep.getGroupId}:${dep.getArtifactId}:${dep.getType}"),
        Some(s"${dep.getGroupId}:${dep.getArtifactId}:jar"),
      ).flatten

      keys
        .flatMap(k => versionMap.flatMap(m => Option(m.get(k))))
        .headOption
        .map(_.getBaseVersion)
        .orNull
    }

  private def dependencyKey(
      dep: Dependency,
      project: MavenProject,
  ): Option[ArtifactKey] =
    Option(resolveVersion(dep, project))
      .filter(_.nonEmpty)
      .map { version =>
        ArtifactKey(
          groupId = dep.getGroupId,
          artifactId = dep.getArtifactId,
          version = version,
          classifier = dependencyClassifier(dep),
          extension = dependencyExtension(dep),
        )
      }

  private def artifactKey(artifact: Artifact): ArtifactKey =
    ArtifactKey(
      groupId = artifact.getGroupId,
      artifactId = artifact.getArtifactId,
      version = artifact.getVersion,
      classifier = Option(artifact.getClassifier).filter(_.nonEmpty),
      extension = artifactExtension(artifact),
    )

  private def artifactKey(
      artifact: org.eclipse.aether.artifact.Artifact
  ): ArtifactKey =
    ArtifactKey(
      groupId = artifact.getGroupId,
      artifactId = artifact.getArtifactId,
      version = artifact.getVersion,
      classifier = Option(artifact.getClassifier).filter(_.nonEmpty),
      extension = artifact.getExtension,
    )

  private def artifactExtension(artifact: Artifact): String =
    Option(artifact.getArtifactHandler)
      .flatMap(handler => Option(handler.getExtension))
      .filter(_.nonEmpty)
      .orElse(Option(artifact.getType).filter(_.nonEmpty))
      .getOrElse("jar")

  private def dependencyExtension(dep: Dependency): String =
    Option(dep.getType)
      .filter(v => v.nonEmpty && v != "test-jar")
      .getOrElse("jar")

  private def dependencyClassifier(dep: Dependency): Option[String] =
    Option(dep.getClassifier)
      .filter(_.nonEmpty)
      .orElse(Option.when(dep.getType == "test-jar")("tests"))

  private def localArtifactPath(
      base: File,
      g: String,
      a: String,
      v: String,
      classifier: Option[String],
      extension: String,
  ): Option[File] =
    if (extension != "jar") None
    else {
      val classifierSuffix = classifier.map("-" + _).getOrElse("")
      // Resolved SNAPSHOT versions (e.g. 1.5.14-20260226.050240-62) are stored
      // under the base version directory (1.5.14-SNAPSHOT) in the local repo.
      val dir = v.replaceAll("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT")
      val jar = new File(
        base,
        s"${g.replace('.', '/')}/$a/$dir/$a-$v$classifierSuffix.$extension",
      )
      Option.when(jar.isFile)(jar)
    }

  private def isJar(file: File): Boolean =
    file.getName.endsWith(".jar")

  private def localSourcesJarPath(
      base: File,
      g: String,
      a: String,
      v: String,
  ): File = {
    val dir = v.replaceAll("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT")
    new File(base, s"${g.replace('.', '/')}/$a/$dir/$a-$v-sources.jar")
  }
}
