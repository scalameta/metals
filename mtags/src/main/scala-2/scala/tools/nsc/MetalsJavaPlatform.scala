package scala.tools.nsc

import scala.tools.nsc.backend.JavaPlatform
import scala.tools.nsc.util.ClassPath

import scala.meta.internal.pc.classpath.MtagsClassPathFactory
import scala.meta.internal.pc.classpath.MtagsPathResolver

// we need to define it in scala.tools.nsc because `currentClassPath` is package private
trait MetalsJavaPlatform extends JavaPlatform {
  val rootPackage: LogicalPackage

  override def classPath: ClassPath = {
    if (currentClassPath.isEmpty) {
      val cpFactory = new MtagsClassPathFactory(
        global.settings,
        rootPackage,
        global.closeableRegistry
      )
      val resolver = new MtagsPathResolver(
        global.settings,
        cpFactory,
        global.closeableRegistry
      )
      currentClassPath = Some(resolver.result)
    }
    currentClassPath.get
  }
}
