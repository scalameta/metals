package scala.meta.internal.pc.classpath

import java.io.File

import scala.tools.nsc.CloseableRegistry
import scala.tools.nsc.LogicalPackage
import scala.tools.nsc.LogicalSourcePath
import scala.tools.nsc.Settings
import scala.tools.nsc.classpath.ClassPathFactory
import scala.tools.nsc.util.ClassPath

/**
 * A ClassPath factory for source elements, based on logical packages.
 */
class MtagsClassPathFactory(
    settings: Settings,
    rootPackage: LogicalPackage,
    closeableRegistry: CloseableRegistry
) extends ClassPathFactory(settings, closeableRegistry) {

  /**
   * Creators for sub classpaths which preserve this context.
   */
  override def sourcesInPath(path: String): List[ClassPath] = {
    List(
      new LogicalSourcePath(
        path.split(File.pathSeparator).map(new File(_)),
        rootPackage
      )
    )
  }
}
