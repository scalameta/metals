package scala.meta.internal.builds
import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import scala.util.Properties
import scala.meta.internal.metals.MetalsServerConfig

object GradleBuildTool extends BuildTool {

  private val initScriptName = "init-script.gradle"

  private val versionsArray =
    BuildInfo.supportedScalaVersions
      .map(s => scala.meta.Lit.String(s).syntax)
      .mkString("[", ",", "]")

  private val initScript =
    s"""
       |initscript {
       |    repositories {
       |        mavenCentral()
       |        // This might be removed when updating gradle bloop version to a full one
       |        maven{
       |          url 'https://dl.bintray.com/scalacenter/releases'
       |        }
       |    }
       |
       |    dependencies {
       |        classpath 'ch.epfl.scala:gradle-bloop_2.11:${BuildInfo.gradleBloopVersion}'
       |    }
       |}
       |allprojects {
       |    apply plugin: bloop.integrations.gradle.BloopPlugin
       |    afterEvaluate {
       |        Dependency scalaLib = project.configurations.collect {
       |            it.dependencies.find {
       |                it.name == 'scala-library'
       |            }
       |        }.find { it }
       |        Set versions = $versionsArray
       |        if(!scalaLib){
       |          logger.warn('No scala library is configured, cannot determine version.')
       |        } else if (!versions.contains(scalaLib.version)){
       |          logger.warn('Unsupported scala version ' + scalaLib.version)
       |        } else {
       |          repositories {
       |              mavenCentral()
       |          }
       |          configurations {
       |              scalaCompilerPlugin
       |          }
       |          dependencies {
       |              scalaCompilerPlugin 'org.scalameta:semanticdb-scalac_' + scalaLib.version + ':4.1.4'
       |          }
       |          String semanticDb = configurations.scalaCompilerPlugin.asPath.split(':').find{it.contains('semanticdb')}
       |          if (!semanticDb) {
       |              throw new RuntimeException("SemanticDB plugin not found!")
       |          }
       |          tasks.withType(ScalaCompile) {
       |              def params = [
       |                      '-Xplugin:' + semanticDb,
       |                      '-P:semanticdb:synthetics:on',
       |                      '-P:semanticdb:failures:warning',
       |                      '-P:semanticdb:sourceroot:' + project.rootProject.projectDir,
       |                      '-Yrangepos',
       |                      '-Xplugin-require:semanticdb'
       |              ]
       |              if (scalaCompileOptions.additionalParameters)
       |                scalaCompileOptions.additionalParameters += params
       |              else
       |                scalaCompileOptions.additionalParameters = params
       |          }
       |        }
       |    }
       |}
    """.stripMargin.getBytes()

  private lazy val tempDir = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  private lazy val initScriptPath: Path = {
    Files.write(tempDir.resolve(initScriptName), initScript)
  }
  private lazy val embeddedGradleLauncher: AbsolutePath = {
    val gradleWrapper =
      if (Properties.isWin) "gradlew.bat"
      else "gradlew"
    val out = BuildTool.copyFromResource(tempDir, gradleWrapper)
    out.toFile.setExecutable(true)
    Set(s"gradle-wrapper.jar", "gradle-wrapper.properties").foreach {
      fileName =>
        BuildTool.copyFromResource(tempDir, s"gradle/wrapper/$fileName")
    }
    AbsolutePath(out)
  }

  override def toString: String = "gradle"

  override def digest(
      workspace: AbsolutePath
  ): Option[String] = GradleDigest.current(workspace)

  override def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String] = {
    userConfig().gradleScript match {
      case Some(script) =>
        List(script, "bloopInstall", "--initScript", initScriptPath.toString)
      case None =>
        List(
          embeddedGradleLauncher.toString(),
          "bloopInstall",
          "--init-script",
          initScriptPath.toString
        )
    }
  }

  // @tgodzik This this is the wrapper version we specify it as such,
  // since it's hard to determine which version will be used as gradle
  // doesn't save it in any settings
  override def version: String = "5.3.1"

  override def minimumVersion: String = "3.0.0"

  def isGradleRelatedPath(workspace: AbsolutePath, path: AbsolutePath) = {
    val buildSrc = workspace.toNIO.resolve("buildSrc")
    val filename = path.toNIO.getFileName.toString
    path.toNIO.startsWith(buildSrc) ||
    filename.endsWith(".gradle") ||
    filename.endsWith(".gradle.kts")
  }
}
