package scala.meta.internal.metals.mbt

import java.lang
import java.nio.file.ClosedFileSystemException
import java.util.concurrent.atomic.AtomicBoolean
import java.{util => ju}
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.pc.SemanticdbCompilationUnit

trait PackageListing {
  def listPackage(packageName: String): ju.List[SemanticdbCompilationUnit]
}

class JavacSourcepathFileManager(
    delegate: JavaFileManager,
    packageListing: PackageListing,
) extends ForwardingJavaFileManager[JavaFileManager](delegate) {

  private val isClosed = new AtomicBoolean(false)

  override def close(): Unit = {
    if (isClosed.compareAndSet(false, true)) {
      try super.close()
      catch {
        // Ignore, happens when the jar file has been deleted
        case _: java.nio.file.NoSuchFileException =>
      }
    }
  }

  // Convert SourceFile -> Fully Qualified Name ("java.io.File")
  override def inferBinaryName(
      location: JavaFileManager.Location,
      file: JavaFileObject,
  ): String = try {
    file match {
      case s: SemanticdbCompilationUnit =>
        s.binaryName()
      case _ =>
        // NOTE: doing `super.inferBinaryName` with a custom `JavaFileObject`
        // implementation causes the compiler to throw an error like this:
        //   java.lang.RuntimeException: java.lang.IllegalArgumentException: path.to.YourCustomClassName
        super.inferBinaryName(location, file)
    }
  } catch {
    case _: ClosedFileSystemException | _: ju.ConcurrentModificationException =>
      // Happens for cancelled tasks
      ""
    case NonFatal(e) =>
      val fallback =
        file.toUri().toString().stripSuffix(".java").stripPrefix("file://")
      scribe.error(
        s"PruneCompilerFileManager: failed to infer binary name for '${file.toUri()}'. Falling back to '${fallback}' to let compilation continue.",
        e,
      )
      fallback
  }

  // Convert package FQN ("java.io") into a list of source files defined in that package
  override def list(
      location: JavaFileManager.Location,
      packageName: String,
      kinds: ju.Set[JavaFileObject.Kind],
      recurse: Boolean,
  ): lang.Iterable[JavaFileObject] = try {
    location.getName() match {
      case "SOURCE_PATH" =>
        val pkgSymbol = packageName.replace('.', '/') + '/'
        val result =
          packageListing.listPackage(pkgSymbol).asScala.map {
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
  } catch {
    case _: ClosedFileSystemException | _: ju.ConcurrentModificationException =>
      // Happens for cancelled tasks
      ju.Collections.emptyList()
    case NonFatal(e) =>
      scribe.error(
        s"PruneCompilerFileManager: failed to list files for package '$packageName' at location '$location'. Returning an empty list to let compilation continue.",
        e,
      )
      ju.Collections.emptyList()
  }
}
