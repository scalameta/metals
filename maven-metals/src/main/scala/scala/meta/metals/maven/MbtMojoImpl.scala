package scala.meta.metals.maven

import java.io.File
import java.nio.file.Files
import java.{util => ju}

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.google.gson.GsonBuilder
import org.apache.maven.model.Dependency
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject

object MbtMojoImpl {

  private def emit(msg: String): Unit = { println(msg); System.out.flush() }

  def run(mojo: MbtMojo): Unit = {
    val log = mojo.getLog
    val reactorProjects = mojo.getReactorProjects.asScala.toList
    val (allProjects, profileMap) =
      MavenProfileModules.includeProfileModules(reactorProjects, mojo, log)

    val projects = allProjects
      .filterNot(_.getPackaging == "pom")
      .toList

    emit(s"[metals-maven] exporting ${projects.size} module(s)")

    val reactorByCoords =
      projects.map(projectCoords).toMap
    val reactorCoords = reactorByCoords.keySet
    val localRepoBase = mojo.getLocalRepositoryBasedir

    val allExternalCoords =
      MavenDependencyResolver.externalCoords(projects, reactorCoords)
    val sourcesCache =
      resolveSourcesCache(allExternalCoords, localRepoBase, mojo, log)
    val artifactFiles =
      MavenDependencyResolver.resolveDependencyJars(
        projects,
        reactorCoords,
        localRepoBase,
        mojo,
        log,
        emit,
      )

    val depModuleMap = new ju.LinkedHashMap[String, DepModuleEntry]()
    val namespaces = new ju.LinkedHashMap[String, NamespaceJson]()

    for (project <- projects) {
      val mainConfig = MavenCompilerConfig.extract(project, isTest = false)
      val testConfig = MavenCompilerConfig.extract(project, isTest = true)
      val mainJavaHome =
        JavaHomeResolver
          .resolve(mainConfig.javacOptions, project, isTest = false, mojo)
          .orNull
      val testJavaHome =
        JavaHomeResolver
          .resolve(testConfig.javacOptions, project, isTest = true, mojo)
          .orNull
      val mainName =
        s"${project.getGroupId}:${project.getArtifactId}:${project.getVersion}"

      val activeProfiles = profileMap.getOrElse(project, Set.empty[String])
      emit(s"[metals-maven] namespace: $mainName")
      namespaces.put(
        mainName,
        buildNamespace(
          project,
          mainName,
          isTest = false,
          reactorByCoords,
          depModuleMap,
          mainConfig,
          mainJavaHome,
          localRepoBase,
          artifactFiles,
          sourcesCache,
          activeProfiles,
          mojo,
          log,
        ),
      )
      namespaces.put(
        s"$mainName:test",
        buildNamespace(
          project,
          mainName,
          isTest = true,
          reactorByCoords,
          depModuleMap,
          testConfig,
          testJavaHome,
          localRepoBase,
          artifactFiles,
          sourcesCache,
          activeProfiles,
          mojo,
          log,
        ),
      )
    }

    val build = MbtBuildJson(
      dependencyModules = new ju.ArrayList[DepModuleEntry](depModuleMap.values),
      namespaces = namespaces,
    )

    val out = mojo.getOutputFile.toPath
    Option(out.getParent).foreach(p => Files.createDirectories(p))
    val gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create()
    Files.writeString(out, gson.toJson(build))
    emit(
      s"[metals-maven] wrote ${namespaces.size()} namespaces, ${depModuleMap.size()} dependencies → $out"
    )
  }

  private def resolveSourcesCache(
      allExternalCoords: Set[(String, String, String)],
      localRepoBase: File,
      mojo: MbtMojo,
      log: Log,
  ): Map[String, File] =
    if (mojo.isDownloadSources) {
      emit(
        s"[metals-maven] resolving sources JARs for ${allExternalCoords.size} dependencies..."
      )
      val result = MavenDependencyResolver.resolveSourcesJarsBatch(
        allExternalCoords,
        localRepoBase,
        mojo,
        log,
      )
      emit(
        s"[metals-maven] sources resolved: ${result.size}/${allExternalCoords.size}"
      )
      result
    } else {
      val result =
        MavenDependencyResolver.resolveLocalSourcesOnly(
          allExternalCoords,
          localRepoBase,
        )
      emit(
        s"[metals-maven] sources from local cache: ${result.size}/${allExternalCoords.size}"
      )
      result
    }

  private def buildNamespace(
      project: MavenProject,
      mainName: String,
      isTest: Boolean,
      reactorByCoords: Map[(String, String, String), MavenProject],
      depModuleMap: ju.LinkedHashMap[String, DepModuleEntry],
      compilerConfig: CompilerConfig,
      javaHome: String,
      localRepoBase: File,
      artifactFiles: Map[MavenDependencyResolver.ArtifactKey, File],
      sourcesCache: Map[String, File],
      activeProfiles: Set[String],
      mojo: MbtMojo,
      log: Log,
  ): NamespaceJson = {
    val roots =
      if (isTest) project.getTestCompileSourceRoots
      else project.getCompileSourceRoots

    NamespaceJson(
      sources = MavenSourceRoots.existingSources(roots, project, isTest).asJava,
      scalacOptions = compilerConfig.scalacOptions.asJava,
      javacOptions = (compilerConfig.javacOptions ++ MavenDependencyResolver
        .annotationProcessorPathArgs(
          compilerConfig.annotationProcessorPaths,
          artifactFiles,
          localRepoBase,
          mojo,
          log,
        )).asJava,
      dependencyModules = MavenDependencyResolver
        .collectDeps(
          project,
          isTest,
          reactorByCoords,
          depModuleMap,
          localRepoBase,
          artifactFiles,
          sourcesCache,
          log,
        )
        .asJava,
      scalaVersion = compilerConfig.scalaVersion.orNull,
      javaHome = javaHome,
      dependsOn = computeDependsOn(
        project,
        mainName,
        isTest,
        reactorByCoords,
        mojo,
      ).asJava,
      classDirectory = {
        val dir =
          if (isTest) project.getBuild.getTestOutputDirectory
          else project.getBuild.getOutputDirectory
        if (dir == null || dir.isEmpty) null else dir
      },
      configurations =
        if (activeProfiles.isEmpty) null
        else
          new ju.ArrayList(
            List("-P", activeProfiles.toList.sorted.mkString(",")).asJava
          ),
    )
  }

  private def computeDependsOn(
      project: MavenProject,
      mainName: String,
      isTest: Boolean,
      reactorByCoords: Map[(String, String, String), MavenProject],
      mojo: MbtMojo,
  ): List[String] = {
    val graphUpstream = Try {
      mojo.getSession.getProjectDependencyGraph
        .getUpstreamProjects(project, false)
        .asScala
        .toList
    }.toOption

    val artifactUpstream =
      project.getArtifacts.asScala
        .flatMap(a =>
          reactorByCoords.get((a.getGroupId, a.getArtifactId, a.getVersion))
        )
        .toList

    val declaredUpstream =
      directDeclaredReactorDeps(project, isTest, reactorByCoords)

    val upstream =
      graphUpstream match {
        case Some(fromGraph) =>
          (fromGraph ++ declaredUpstream).distinct
        case None =>
          (artifactUpstream ++ declaredUpstream).distinct
      }
    val compileCoords = compileReactorCoords(project, reactorByCoords)
    val filteredUpstream =
      if (isTest) upstream.filter(_.getPackaging != "pom")
      else
        upstream.filter(p =>
          p.getPackaging != "pom" &&
            compileCoords.contains(projectCoords(p)._1)
        )

    val mainDepsOn = filteredUpstream.map(p =>
      s"${p.getGroupId}:${p.getArtifactId}:${p.getVersion}"
    )
    val testJarDepsOn =
      if (!isTest) Nil
      else testJarNamespaceDeps(project, reactorByCoords)
    val selfMain = if (isTest) List(mainName) else Nil

    (selfMain ++ mainDepsOn ++ testJarDepsOn).distinct
  }

  private def compileReactorCoords(
      project: MavenProject,
      reactorByCoords: Map[(String, String, String), MavenProject],
  ): Set[(String, String, String)] = {
    val fromArtifacts = project.getArtifacts.asScala
      .filter(a => MavenDependencyResolver.CompileScopes.contains(a.getScope))
      .flatMap(a =>
        reactorByCoords.get((a.getGroupId, a.getArtifactId, a.getVersion))
      )
      .map(projectCoords(_)._1)

    val fromDeclared = project.getDependencies.asScala
      .filter(d => MavenDependencyResolver.CompileScopes.contains(d.getScope))
      .flatMap { d =>
        val version = resolvedDependencyVersion(d, project).orNull
        reactorByCoords.get((d.getGroupId, d.getArtifactId, version))
      }
      .map(projectCoords(_)._1)

    (fromArtifacts ++ fromDeclared).toSet
  }

  private def directDeclaredReactorDeps(
      project: MavenProject,
      isTest: Boolean,
      reactorByCoords: Map[(String, String, String), MavenProject],
  ): List[MavenProject] =
    project.getDependencies.asScala.toList
      .filter { dep =>
        isTest || MavenDependencyResolver.CompileScopes.contains(dep.getScope)
      }
      .flatMap { dep =>
        val version = resolvedDependencyVersion(dep, project).orNull
        reactorByCoords.get((dep.getGroupId, dep.getArtifactId, version))
      }
      .distinct

  private def testJarNamespaceDeps(
      project: MavenProject,
      reactorByCoords: Map[(String, String, String), MavenProject],
  ): List[String] =
    project.getDependencies.asScala
      .filter(d =>
        d.getType == "test-jar" || Option(d.getClassifier).contains("tests")
      )
      .flatMap { d =>
        val version = resolvedDependencyVersion(d, project).orNull
        reactorByCoords.get((d.getGroupId, d.getArtifactId, version))
      }
      .map(p => s"${p.getGroupId}:${p.getArtifactId}:${p.getVersion}:test")
      .toList

  private def resolvedDependencyVersion(
      d: Dependency,
      project: MavenProject,
  ): Option[String] =
    Option(d.getVersion).filter(_.nonEmpty).orElse {
      val versionMap = Option(project.getManagedVersionMap)
      val classifier = Option(d.getClassifier).filter(_.nonEmpty)
      // Maven keys: groupId:artifactId:type[:classifier]
      val keys = List(
        classifier
          .map(c => s"${d.getGroupId}:${d.getArtifactId}:${d.getType}:$c"),
        Some(s"${d.getGroupId}:${d.getArtifactId}:${d.getType}"),
        Some(s"${d.getGroupId}:${d.getArtifactId}:jar"),
      ).flatten
      keys
        .flatMap(k => versionMap.flatMap(m => Option(m.get(k))))
        .headOption
        .map(_.getBaseVersion)
    }

  private def projectCoords(
      project: MavenProject
  ): ((String, String, String), MavenProject) =
    (project.getGroupId, project.getArtifactId, project.getVersion) -> project
}

private[maven] case class MbtBuildJson(
    dependencyModules: ju.Collection[DepModuleEntry],
    namespaces: ju.Map[String, NamespaceJson],
)

private[maven] case class DepModuleEntry(
    id: String,
    jar: String,
    sources: String,
)

private[maven] case class NamespaceJson(
    sources: ju.List[String],
    scalacOptions: ju.List[String],
    javacOptions: ju.List[String],
    dependencyModules: ju.List[String],
    scalaVersion: String,
    javaHome: String,
    dependsOn: ju.List[String],
    classDirectory: String,
    configurations: ju.List[String],
)
