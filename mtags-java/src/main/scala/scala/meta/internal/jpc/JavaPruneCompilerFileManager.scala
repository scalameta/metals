package scala.meta.internal.jpc

import java.lang
import java.util.concurrent.atomic.AtomicBoolean
import java.{util => ju}
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._

import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SemanticdbFileManager

import org.slf4j.Logger

class PruneCompilerFileManager(
    delegate: JavaFileManager,
    semanticdbFileManager: SemanticdbFileManager,
    @nowarn("cat=unused")
    logger: Logger
) extends ForwardingJavaFileManager[JavaFileManager](delegate) {

  private val isClosed = new AtomicBoolean(false)

  override def close(): Unit = {
    if (isClosed.compareAndSet(false, true)) {
      super.close()
    }
  }

  // Convert SourceFile -> Fully Qualified Name ("java.io.File")
  override def inferBinaryName(
      location: JavaFileManager.Location,
      file: JavaFileObject
  ): String = {
    file match {
      case s: SemanticdbCompilationUnit =>
        s.toplevelSymbols().asScala.headOption match {
          case Some(sym) =>
            // Convert SemanticDB symbol "java/io/File#" into a Java FQN "java.io.File"
            sym.stripSuffix("#").stripSuffix(".").replace('/', '.')
          case None =>
            super.inferBinaryName(location, file)
        }
      case _ =>
        super.inferBinaryName(location, file)
    }
  }

  // Convert package FQN ("java.io") into a list of source files defined in that package
  override def list(
      location: JavaFileManager.Location,
      packageName: String,
      kinds: ju.Set[JavaFileObject.Kind],
      recurse: Boolean
  ): lang.Iterable[JavaFileObject] = {
    location.getName() match {
      case "SOURCE_PATH" =>
        val pkgSymbol = packageName.replace('.', '/') + '/'
        val result =
          semanticdbFileManager.listPackage(pkgSymbol).asScala.map {
            case j: JavaFileObject => j: JavaFileObject
            case els =>
              throw new IllegalArgumentException(
                s"Expected JavaFileObject, got $els"
              )
          }
        result.asJava
      case _ =>
        super.list(location, packageName, kinds, recurse)
    }
  }
}
