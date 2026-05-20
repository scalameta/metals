package scala.meta.internal.metals.mbt.importer

import java.net.URLClassLoader
import java.nio.file.Files
import java.util.ServiceLoader

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.GradleDigest
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.io.AbsolutePath
import scala.meta.mbt.MbtExtractor

/**
 * Extracts [[MbtBuild]] from a Gradle project using the gradle-extractor module.
 * The extractor is downloaded on-demand using Coursier and loaded via ServiceLoader.
 */
class GradleMbtImporter(
    val projectRoot: AbsolutePath
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  override val name: String = "gradle"

  override def extract(workspace: AbsolutePath): Future[Unit] = Future {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)

    val timer = new Timer(Time.system)
    val extractor = loadExtractor()
    extractor.extract(projectRoot.toNIO, out.toNIO)
    scribe.info(s"time: gradle-extractor extract in $timer")
  }

  override def isBuildRelated(path: AbsolutePath): Boolean =
    GradleBuildTool.isGradleRelatedPath(projectRoot, path)

  override def digest(workspace: AbsolutePath): Option[String] =
    GradleDigest.current(projectRoot)

  private def loadExtractor(): MbtExtractor = {
    val jars = Embedded.downloadGradleExtractor()
    val urls = jars.map(_.toUri.toURL).toArray
    val classloader = new URLClassLoader(urls, this.getClass.getClassLoader)
    val services =
      ServiceLoader.load(classOf[MbtExtractor], classloader).iterator()
    if (services.hasNext) services.next()
    else {
      val cls = classloader.loadClass("gradleinfo.GradleInfoExtractorImpl")
      val ctor = cls.getDeclaredConstructor()
      ctor.setAccessible(true)
      ctor.newInstance().asInstanceOf[MbtExtractor]
    }
  }
}
