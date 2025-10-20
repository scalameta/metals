package scala.meta.internal.jpc

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager

import scala.jdk.CollectionConverters._

import scala.meta.pc

case class PruneJavaFile(
    source: JavaFileObject,
    patch: Option[PatchedModule]
)

object PruneJavaFile {
  def simple(params: pc.VirtualFileParams): PruneJavaFile = {
    PruneJavaFile(
      SourceJavaFileObject.make(params.text(), params.uri()),
      None
    )
  }
  def fromParams(
      params: pc.VirtualFileParams,
      embedded: pc.EmbeddedClient,
      standardFileManager: StandardJavaFileManager
  ): PruneJavaFile = {
    val uri = params.uri().toString()
    // We are special casing JDK sources here but we may need to generalize this
    // in the future for any module. The problem is that we can't just compile JDK
    // source in a random directory because the same package is already defined by
    // JDK modules. We fix it by adding `--patch-module MODULE_NAME=SOURCE_ROOT`.
    uri.split("src.zip!", 2) match {
      case Array(_, filename) =>
        // This is a JDK source and we need special handling because of modules
        val path =
          embedded.targetDir().resolve(filename.stripPrefix("/"))
        Files.createDirectories(path.getParent())
        Files.write(path, params.text().getBytes(StandardCharsets.UTF_8))
        val fileObjects =
          standardFileManager.getJavaFileObjectsFromPaths(List(path).asJava)
        val moduleName =
          Paths.get(filename).iterator().next().getFileName().toString()
        val patch =
          new PatchedModule(moduleName, embedded.targetDir().toString())
        PruneJavaFile(fileObjects.asScala.head, Some(patch))
      case _ =>
        lazy val abspath = Paths.get(URI.create(uri))
        val jdkSourcesReadonlyDir = embedded.jdkSourcesReadonlyDir()
        // For clients that don't support virtual files, JDK sources live in
        // the WORKSPACE/.metals/readonly/dependencies/src.zip/... directory
        if (
          jdkSourcesReadonlyDir != null &&
          params.uri.getScheme == "file" &&
          abspath.startsWith(embedded.jdkSourcesReadonlyDir())
        ) {
          val relativePath =
            embedded.jdkSourcesReadonlyDir().relativize(abspath)
          // Pick the name of the first child in the src.zip file. For
          // example, extract "java.base" from
          // "java.base/java/lang/Object.java".
          val moduleName =
            relativePath.iterator().next().getFileName().toString()
          val patch = Some(
            new PatchedModule(
              moduleName,
              embedded.jdkSourcesReadonlyDir().toString()
            )
          )
          // Use regular file sources for the JDK sources so that --patch-module works as expected.
          val source = standardFileManager
            .getJavaFileObjectsFromPaths(List(abspath).asJava)
            .asScala
            .head
          PruneJavaFile(source, patch)
        } else {
          val source = SourceJavaFileObject.make(params.text(), params.uri())
          PruneJavaFile(source, None)
        }
    }
  }
}
