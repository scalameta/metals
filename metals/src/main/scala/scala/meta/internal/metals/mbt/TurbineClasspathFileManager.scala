package scala.meta.internal.metals.mbt

import java.nio.file.ClosedFileSystemException
import java.{util => ju}
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardLocation

import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer
import scala.util.control.NonFatal

import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.pc.SemanticdbCompilationUnit

import com.google.turbine.binder.ClassPath

class TurbineClasspathFileManager(
    delegate: JavaFileManager,
    workspaceClasspath: () => TurbineCompileResult,
    listSourcepath: String => java.lang.Iterable[JavaFileObject],
    isDeleted: String => Boolean,
    projectClasspath: ClassPath,
) extends ForwardingJavaFileManager[JavaFileManager](delegate) {

  override def contains(
      location: JavaFileManager.Location,
      file: FileObject,
  ): Boolean = {
    if (SourceJavaFileObject.isOriginalURI(file.getName()))
      true
    else
      super.contains(location, file)
  }

  override def inferBinaryName(
      location: JavaFileManager.Location,
      file: JavaFileObject,
  ): String = try {
    location match {
      case StandardLocation.CLASS_PATH =>
        file match {
          case c: TurbineClassfileObject =>
            c.binaryName
          case _ =>
            super.inferBinaryName(location, file)
        }
      case StandardLocation.SOURCE_PATH =>
        file match {
          case s: SemanticdbCompilationUnit =>
            s.binaryName()
          case _ =>
            super.inferBinaryName(location, file)
        }
      case _ =>
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

  override def list(
      location: JavaFileManager.Location,
      packageName: String,
      kinds: ju.Set[JavaFileObject.Kind],
      recurse: Boolean,
  ): java.lang.Iterable[JavaFileObject] = try {
    location match {
      case StandardLocation.CLASS_PATH =>
        val packageNames = packageName.split('.')
        val turbinePackageName = packageNames.mkString("/")
        val objects = new ju.ArrayList[JavaFileObject]()
        val cp = workspaceClasspath()
        cp.symbolsByPackage.get(turbinePackageName) match {
          case None =>
          case Some(values) =>
            val it = values.iterator()
            while (it.hasNext()) {
              val sym = it.next()
              val binaryName = sym.binaryName()
              // Skip classes that have been deleted but not yet recompiled,
              // or have a pending source on SOURCE_PATH (so javac uses the updated source)
              if (!isDeleted(binaryName)) {
                val bytes = cp.lowered.bytes().get(binaryName)
                if (bytes != null) {
                  val obj = new TurbineClassfileObject(
                    binaryName,
                    () => bytes,
                  )
                  objects.add(obj)
                }
              }
            }
        }
        val isAddedBinaryName = new ju.HashSet[String]()
        for {
          cp <- List(
            // Prioritize the project classpath over the fallback classpath
            projectClasspath,
            cp.classpath,
          )
        } listPackageClasspath(cp, packageNames, isAddedBinaryName) { obj =>
          objects.add(obj)
        }
        objects
      case StandardLocation.SOURCE_PATH =>
        listSourcepath(packageName)
      case _ =>
        super.list(location, packageName, kinds, recurse)
    }
  } catch {
    case _: ClosedFileSystemException | _: ju.ConcurrentModificationException =>
      // Happens for cancelled tasks
      ju.Collections.emptyList()
    case NonFatal(e) =>
      scribe.error(
        s"TurbineClasspathFileManager: failed to list for '${location}' and '${packageName}'. Falling back to empty list to let compilation continue.",
        e,
      )
      ju.Collections.emptyList()
  }
  private def listPackageClasspath(
      projectClasspath: ClassPath,
      packageNames: Array[String],
      isAddedBinaryName: ju.HashSet[String],
  )(fn: JavaFileObject => Unit): Unit = {
    val pkgLookup = projectClasspath
      .index()
      .lookupPackage(Buffer.from(packageNames).asJava)
    if (pkgLookup == null) {
      return
    }
    val it = pkgLookup.classes().iterator()
    while (it.hasNext()) {
      val cls = it.next()
      val lazyBytes = projectClasspath.env().get(cls)
      if (lazyBytes != null) {
        val binaryName = cls.binaryName()
        if (isAddedBinaryName.add(binaryName)) {
          fn(new TurbineClassfileObject(binaryName, lazyBytes.bytes()))
        }
      }
    }
  }

}
