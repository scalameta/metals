package scala.meta.internal.builds

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import coursierapi.MavenRepository

case class GradleExperimentalBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BloopInstallProvider
    with VersionRecommendation {

  override def digest(workspace: AbsolutePath): Option[String] =
    GradleDigest.current(projectRoot)

  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val classpathSeparator = java.io.File.pathSeparator
    val classpath = Embedded
      .downloadDependency(
        GradleExperimentalBuildTool.dependency,
        repositories = Seq(GradleExperimentalBuildTool.gradleRepository),
      )
      .mkString(classpathSeparator)
    List(
      JavaBinary(userConfig().javaHome),
      "-classpath",
      classpath,
      GradleExperimentalBuildTool.mainClass,
    )
  }

  // current maximum supported version - just a placeholder, not tested for
  override def version: String = "8.14.1"

  override def minimumVersion: String = "2.12.0"

  override def recommendedVersion: String = version

  override def toString: String = "Gradle (Experimental)"

  def executableName = GradleExperimentalBuildTool.name
}

object GradleExperimentalBuildTool {
  val name = "gradle (experimental)"

  val mainClass = "com.microsoft.java.bs.core.BloopExporter"

  val gradleRepository: MavenRepository = MavenRepository.of(
    "https://repo.gradle.org/gradle/libs-releases"
  )

  val dependency: Dependency = Dependency.of(
    "io.github.arthurm1.gradle.bsp",
    "server",
    BuildInfo.gradleBspVersion,
  )

  def isGradleRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean = {
    val buildSrc = workspace.toNIO.resolve("buildSrc")
    val filename = path.toNIO.getFileName.toString
    path.toNIO.startsWith(buildSrc) ||
    filename.endsWith(".gradle") ||
    filename.endsWith(".gradle.kts")
  }
}
