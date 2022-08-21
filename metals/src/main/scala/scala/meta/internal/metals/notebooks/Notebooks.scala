package scala.meta.internal.metals.notebooks

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.BuildTargets
import java.io.File
import coursierapi.Dependency
import scala.meta.internal.builds.ShellRunner
import coursierapi.Fetch
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.SourceMapper
import scala.collection.JavaConverters._
import coursierapi.ScalaVersion
import coursierapi.{Cache, Dependency, Fetch, Logger, ResolutionParams, Version}
import coursier.launcher.{
  BootstrapGenerator,
  ClassLoaderContent,
  ClassPathEntry
}

object Notebooks {

  def setupKernel(
      path: AbsolutePath,
      buildTargets: BuildTargets,
      shellRunner: ShellRunner,
      sourceMapper: SourceMapper
  ) = {
    scribe.info(s"Setting up kernel for ${path.toString}")
    try {
      val targets = buildTargets.allScala.map(t => (t, t.baseDirectory)).toList
      // scribe.info(targets.mkString(","))
      val workspaceRoot1 = sourceMapper.workspace()
      val source: AbsolutePath = path

      val parentDir = source.toNIO.getParent()
      val parents = targets.filter { possibleParent =>
        val tmp = possibleParent._2.replace("file://", "")
        scribe.info(parentDir.toString())
        parentDir.startsWith(tmp)
      }

      val target = parents.maxBy(_._2.length())._1
      val cp = target.fullClasspath.map(_.toString())
      val projectId = target.info.getId().toString()

      scribe.info(pprint.apply(target.info).plainText)

      scribe.info(target.scalaInfo.getScalaVersion())

      scribe.info(pprint.apply(target).plainText)

      scribe.info(projectId)
      // val parents = targets.filter(possibleParent => source.toNIO.startsWith(possibleParent.toNIO))
      // scribe.info(parents.toArray.length.toString()  )
      val jvmReprRepo = coursierapi.MavenRepository.of(
        "https://maven.imagej.net/content/repositories/public/"
      )
      // TODO check scala version is valid. For now use 2.13.7
      val scalaVersion = target.scalaInfo.getScalaVersion()

      val almondDep = Dependency.of(
        "sh.almond",
        s"scala-kernel_$scalaVersion",
        BuildInfo.almondVersion
      )

      // val scalaVersionC = ScalaVersion.of( scalaVersion)
      // scribe.info("scala version " + scalaVersionC.toString())
      val f = Fetch.create()
      f.addDependencies(almondDep)
      f.addRepositories(jvmReprRepo)

      val launcherDeps =
        f.fetch().asScala.toList.map(_.toString()).mkString(":")

      // I am not clear, if I am capable of actually doing this.
      // TODO : https://github.com/coursier/coursier/discussions/2479
      // val classPath = (coursierDeps ++ cp.toSet ).mkString(":")

      scribe.info(coursierDeps.mkString(":"))

      val kernelMainClass = "almond.ScalaKernel"
      shellRunner.runJava(
        almondDep,
        kernelMainClass,
        workspaceRoot1,
        List(
          "--install",
          "--command",
          s"""java -cp $launcherDeps $kernelMainClass""",
          "--id",
          "metalsAlmond",
          "--display-name",
          s"metalsAlmond",
          "--global",
          "true",
          "--force",
          "true"
        ),
        false,
        extraRepos = Array(jvmReprRepo)
      )
    } catch {
      case e: Exception =>
        scribe.error(s"Error installing kernel ", e)
        scribe.error(
          s"Swallowing the above exception so metals doesn't crash"
        )
    }
  }

}
