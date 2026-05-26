package scala.meta.metals.maven

import java.io.File
import java.io.FileReader
import java.util.Properties

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.DefaultProjectBuildingRequest
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectBuildingException
import org.apache.maven.project.ProjectBuildingRequest

private[maven] object MavenProfileModules {

  private case class DiscoveredPom(
      pom: File,
      activeProfiles: Set[String],
  )

  def includeProfileModules(
      reactorProjects: List[MavenProject],
      mojo: MbtMojo,
      log: Log,
  ): (List[MavenProject], Map[MavenProject, Set[String]]) = {
    val reactorByPom =
      reactorProjects
        .flatMap(p => projectPomFile(p).map(_ -> p))
        .toMap

    val seedPoms =
      (reactorByPom.keys ++ sessionPomFiles(mojo)).toList.distinct
    val discovered = discoverPomClosure(
      seedPoms,
      mojo,
      log,
    )

    val missing = discovered.filterNot(d => reactorByPom.contains(d.pom))
    val extraWithProfiles = missing.flatMap { discoveredPom =>
      buildProject(discoveredPom, mojo, log).map(
        _ -> discoveredPom.activeProfiles
      )
    }
    if (extraWithProfiles.nonEmpty) {
      log.info(
        s"metals-maven-plugin: discovered ${extraWithProfiles.size} module(s) from Maven profiles"
      )
    }

    val extraProjects = extraWithProfiles.map(_._1)
    val profileMap: Map[MavenProject, Set[String]] =
      extraWithProfiles.toMap ++ reactorProjects.map(_ -> Set.empty[String])

    val allProjects = (reactorProjects ++ extraProjects).distinctBy(projectKey)
    (allProjects, profileMap)
  }

  private def discoverPomClosure(
      seedPoms: List[File],
      mojo: MbtMojo,
      log: Log,
  ): List[DiscoveredPom] = {
    val queue = mutable.Queue.empty[DiscoveredPom]
    val seen = mutable.LinkedHashMap.empty[File, Set[String]]

    seedPoms.foreach { pom =>
      queue.enqueue(DiscoveredPom(pom.getCanonicalFile, Set.empty))
    }

    while (queue.nonEmpty) {
      val current = queue.dequeue()
      val previousProfiles = seen.getOrElse(current.pom, Set.empty)
      val mergedProfiles = previousProfiles ++ current.activeProfiles

      if (!seen.contains(current.pom) || mergedProfiles != previousProfiles) {
        seen.put(current.pom, mergedProfiles)

        readModules(current.pom, current.activeProfiles, mojo, log).foreach {
          child =>
            queue.enqueue(child)
        }
      }
    }

    seen.toList.map { case (pom, profiles) =>
      DiscoveredPom(pom, profiles)
    }
  }

  private def readModules(
      pom: File,
      inheritedProfiles: Set[String],
      mojo: MbtMojo,
      log: Log,
  ): List[DiscoveredPom] = {
    if (!pom.isFile) return Nil

    val model =
      try {
        val in = new FileReader(pom)
        try new MavenXpp3Reader().read(in)
        finally in.close()
      } catch {
        case e: Exception =>
          log.debug(s"metals-maven-plugin: cannot read modules from $pom", e)
          return Nil
      }

    val base = pom.getParentFile

    val normalModules =
      model.getModules.asScala.toList.flatMap { module =>
        modulePom(
          base,
          interpolateModule(module, model, new Properties(), base, mojo),
        )
          .map(DiscoveredPom(_, inheritedProfiles))
      }

    val inactiveProfiles =
      Option(mojo.getSession)
        .flatMap(session => Option(session.getRequest))
        .flatMap(request => Option(request.getInactiveProfiles))
        .map(_.asScala.toSet)
        .getOrElse(Set.empty[String])

    val profileModules =
      model.getProfiles.asScala.toList
        .filterNot(profile => inactiveProfiles.contains(profile.getId))
        .flatMap { profile =>
          val profileId = Option(profile.getId).filter(_.nonEmpty)
          val profiles = inheritedProfiles ++ profileId
          profile.getModules.asScala.toList.flatMap { module =>
            modulePom(
              base,
              interpolateModule(
                module,
                model,
                profile.getProperties,
                base,
                mojo,
              ),
            )
              .map(DiscoveredPom(_, profiles))
          }
        }

    normalModules ++ profileModules
  }

  private def modulePom(
      base: File,
      modulePath: Option[String],
  ): Option[File] =
    modulePath
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.contains("${"))
      .flatMap { module =>
        val path = new File(base, module)
        val pom =
          if (path.getName == "pom.xml") path
          else new File(path, "pom.xml")

        Option.when(pom.isFile)(pom.getCanonicalFile)
      }

  private def interpolateModule(
      value: String,
      model: Model,
      extraProperties: Properties,
      base: File,
      mojo: MbtMojo,
  ): Option[String] = {
    val props = model.getProperties
    val userProperties =
      Option(mojo.getSession).map(_.getUserProperties)
    val systemProperties =
      Option(mojo.getSession).map(_.getSystemProperties)

    val replaced = "\\$\\{([^}]+)\\}".r.replaceAllIn(
      value,
      m => {
        val key = m.group(1)
        val replacement =
          userProperties
            .flatMap(props => Option(props.getProperty(key)))
            .orElse(
              systemProperties.flatMap(props => Option(props.getProperty(key)))
            )
            .orElse(Option(extraProperties.getProperty(key)))
            .orElse(Option(props.getProperty(key)))
            .orElse {
              key match {
                case "basedir" | "project.basedir" =>
                  Some(base.getAbsolutePath)
                case "project.artifactId" | "artifactId" =>
                  Option(model.getArtifactId)
                case "project.groupId" | "groupId" =>
                  Option(model.getGroupId)
                case "project.version" | "version" =>
                  Option(model.getVersion)
                case _ =>
                  None
              }
            }

        java.util.regex.Matcher.quoteReplacement(
          replacement.getOrElse(m.matched)
        )
      },
    )

    Some(replaced)
  }

  private def buildProject(
      discovered: DiscoveredPom,
      mojo: MbtMojo,
      log: Log,
  ): Option[MavenProject] = {
    val projectBuilder = Option(mojo.getProjectBuilder)
    if (projectBuilder.isEmpty) {
      log.debug(
        s"metals-maven-plugin: cannot build profile-only module ${discovered.pom}: ProjectBuilder is unavailable"
      )
      return None
    }

    val request = newProjectBuildingRequest(mojo)
    request.setRepositorySession(mojo.getRepositorySession)
    request.setProcessPlugins(true)
    request.setResolveDependencies(true)

    val activeProfiles =
      Option(request.getActiveProfileIds)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty[String]) ++ discovered.activeProfiles

    request.setActiveProfileIds(activeProfiles.toList.asJava)

    try {
      Some(projectBuilder.get.build(discovered.pom, request).getProject)
    } catch {
      case e: ProjectBuildingException =>
        log.warn(
          s"metals-maven-plugin: skipping profile-only module ${discovered.pom}: ${e.getMessage}"
        )
        None
    }
  }

  private def newProjectBuildingRequest(
      mojo: MbtMojo
  ): ProjectBuildingRequest =
    Option(mojo.getSession)
      .flatMap(session => Option(session.getProjectBuildingRequest))
      .map(new DefaultProjectBuildingRequest(_))
      .getOrElse(new DefaultProjectBuildingRequest())

  private def sessionPomFiles(mojo: MbtMojo): List[File] =
    Option(mojo.getSession).toList.flatMap { session =>
      List(
        Option(session.getCurrentProject).flatMap(projectPomFile),
        Option(session.getRequest)
          .flatMap(request => Option(request.getPom))
          .filter(_.isFile)
          .map(_.getCanonicalFile),
      ).flatten
    }

  private def projectPomFile(project: MavenProject): Option[File] =
    Option(project.getFile)
      .orElse(Option(project.getBasedir).map(new File(_, "pom.xml")))
      .filter(_.isFile)
      .map(_.getCanonicalFile)

  private def projectKey(project: MavenProject): String =
    projectPomFile(project)
      .map(_.getCanonicalPath)
      .getOrElse(
        s"${project.getGroupId}:${project.getArtifactId}:${project.getVersion}"
      )
}
