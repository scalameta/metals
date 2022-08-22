package scala.meta.internal.metals.notebooks

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.BuildTargets
import java.io.File
import scala.meta.internal.metals.JavaBinary
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
import java.nio.file.Files
import scala.meta.internal.metals.UserConfiguration

object Notebooks {

  def setupKernel(
      path: AbsolutePath,
      buildTargets: BuildTargets,
      shellRunner: ShellRunner,
      sourceMapper: SourceMapper,
      userConfig: () => UserConfiguration,
  ) = {
    scribe.info(s"Setting up kernel for ${path.toString}")
    try {
      val targets = buildTargets.allScala.map(t => (t, t.baseDirectory)).toList
      val kernelMainClass = "almond.ScalaKernel"

      val workspaceRoot1 = sourceMapper.workspace()
      val source: AbsolutePath = path

      val parentDir = source.toNIO.getParent()
      val parents = targets.filter { possibleParent =>
        val tmp = possibleParent._2.replace("file://", "")
        scribe.info(parentDir.toString())
        parentDir.startsWith(tmp)
      }

      val target = parents.maxBy(_._2.length())._1
      val cp = target.fullClasspath
      //val projectId = target.info.getId().toString()

      // scribe.info(pprint.apply(target.info).plainText)

      // scribe.info(target.scalaInfo.getScalaVersion())

      // scribe.info(pprint.apply(target).plainText)

      // scribe.info(projectId)
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

      val launcherDeps = f.fetch().asScala.toVector

      def fileToCpResource(aFile: File) = ClassPathEntry.Resource(aFile.getName(), aFile.lastModified(), Files.readAllBytes(aFile.toPath()) )

      val launcherDepCp = launcherDeps.map(fileToCpResource)

      scribe.info(s"Check paths here")
      val projectDeps = cp.map{aPath => 
        val asFile = aPath.toFile()        
        if (asFile.isFile() && !launcherDeps.contains(asFile) ) {          
          Some(fileToCpResource(aPath.toFile()))
        } else None
      }.flatten

      val sharedContent = ClassLoaderContent(launcherDepCp)
      val mainContent = ClassLoaderContent(projectDeps)
      val params = coursier.launcher.Parameters.Bootstrap(
          Seq(mainContent, sharedContent), 
          kernelMainClass
        )
        .withDeterministic(true)
        .withPreambleOpt(None)
        .withMainClass(kernelMainClass)
        //.withHybridAssembly(true)

      val tmpFile = {
        val prefix = "metalsAlmondTest" 
        val suffix = ".jar"
        Files.createTempFile(prefix, suffix)
      }
      
      Runtime.getRuntime.addShutdownHook(
        new Thread {
          setDaemon(true)
          override def run(): Unit =
            try Files.deleteIfExists(tmpFile)
            catch {
              case e: Exception =>
                scribe.error(s"Ignored error while deleting temporary file $tmpFile: $e")
            }
        }
      )
      scribe.info("so we have a bootstrap file... but what to do with it? ")
      BootstrapGenerator.generate(params, tmpFile)        

      // I am not clear, if I am capable of actually doing this.
      // TODO : https://github.com/coursier/coursier/discussions/2479
      scribe.info(tmpFile.toString())

      shellRunner.run(
        tmpFile.toString(),
        List(
          JavaBinary(userConfig().javaHome),
          "coursier.bootstrap.launcher.ResourcesLauncher",
          "--install",
          // "--command",
          // s"""java -jar /Users/simon/Library/Jupyter/kernels/metalsAlmond/launcher.jar""",
          "--id",
          "metalsAlmond",
          "--display-name",
          s"metalsAlmond",
          "--global",
          "true",
          "--force",
          "true",          
        ),        
        workspaceRoot1,
        false,
        //extraRepos = Array(jvmReprRepo)
      )
    } catch {
      case e: Exception =>
        scribe.error(s"Error installing kernel ", e)
        scribe.error(
          s"Swallowing the above exception so metals doesn't crash"
        )
    }
      
      
    //   shellRunner.runJava(
    //     almondDep,
    //     kernelMainClass,
    //     workspaceRoot1,
    //     List(
    //       "--install",
    //       "--command",
    //       s"""java -cp $tempDeps $kernelMainClass""",
    //       "--id",
    //       "metalsAlmond",
    //       "--display-name",
    //       s"metalsAlmond",
    //       "--global",
    //       "true",
    //       "--force",
    //       "true"
    //     ),
    //     false,
    //     extraRepos = Array(jvmReprRepo)
    //   )
    // } catch {
    //   case e: Exception =>
    //     scribe.error(s"Error installing kernel ", e)
    //     scribe.error(
    //       s"Swallowing the above exception so metals doesn't crash"
    //     )
    // }
  }

}
