package tests.pc

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation

import scala.jdk.CollectionConverters._
import scala.util.Using

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.jpc.JavaPresentationCompiler
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.EmbeddedClient
import scala.meta.pc.JavaFileManagerFactory

import coursierapi.Dependency
import org.slf4j.LoggerFactory
import tests.BaseSuite
import tests.PCSuite

class TestingEmbeddedClient(val tmp: AbsolutePath) extends EmbeddedClient {
  override def javaHeaderCompilerPluginJarPath(): Path = {
    val bytes = InputStreamIO.readBytes(
      this.getClass.getClassLoader
        .getResourceAsStream("java-header-compiler.jar")
    )
    val path = tmp.resolve("java-header-compiler.jar")
    Files.write(path.toNIO, bytes)
    path.toNIO
  }
  override def jdkSourcesReadonlyDir(): Path = tmp.toNIO.resolve("jdk-sources")
  override def targetDir(): Path = tmp.toNIO.resolve("target")
}

abstract class BaseJavaPCSuite extends BaseSuite with PCSuite {

  val documentationHoverEnabled = false

  val dialect: Dialect = dialects.Scala213

  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("java.metals"))

  protected lazy val presentationCompiler: JavaPresentationCompiler = {
    val fetch = createFetch()
    extraDependencies.foreach(fetch.addDependencies(_))
    val myclasspath: Seq[Path] = extraLibraries(fetch)

    val sourcePath: Option[Path] = getSourcePath()
    registerExistingJavaFiles(sourcePath)
    val javaFileManagerFactory: JavaFileManagerFactory =
      (standardFileManager, classpath) => {
        if (myclasspath != null) {
          standardFileManager.setLocationFromPaths(
            StandardLocation.CLASS_PATH,
            myclasspath.asJava,
          )
        }
        new ForwardingJavaFileManager[JavaFileManager](standardFileManager) {
          override def list(
              location: JavaFileManager.Location,
              packageName: String,
              kinds: java.util.Set[JavaFileObject.Kind],
              recurse: Boolean,
          ): java.lang.Iterable[JavaFileObject] = {

            val originalResult =
              super.list(location, packageName, kinds, recurse).asScala.toList
            if (kinds.contains(JavaFileObject.Kind.SOURCE)) {
              val matchingExternal = ExternalSourceStorage.fileObjects.filter {
                f =>
                  val uriStr = f.toUri.toString
                  val pkgPath = packageName.replace('.', '/')
                  if (packageName.isEmpty) true
                  else if (recurse) uriStr.contains(pkgPath)
                  else uriStr.contains("/" + pkgPath + "/")
              }
              (originalResult ++ matchingExternal).asJava
            } else {
              originalResult.asJava
            }
          }
          override def getJavaFileForInput(
              location: JavaFileManager.Location,
              className: String,
              kind: JavaFileObject.Kind,
          ): JavaFileObject = {
            if (kind == JavaFileObject.Kind.SOURCE) {
              ExternalSourceStorage.fileObjects
                .find { f =>
                  val uriStr = f.toUri.toString
                  val expectedEnd = className.replace('.', '/') + ".java"
                  uriStr.endsWith(expectedEnd)
                }
                .getOrElse(super.getJavaFileForInput(location, className, kind))
            } else {
              super.getJavaFileForInput(location, className, kind)
            }
          }

          override def inferBinaryName(
              location: JavaFileManager.Location,
              file: JavaFileObject,
          ): String = {
            file match {
              case inMemory: InMemoryJavaFile =>
                val uriStr = inMemory.toUri.toString
                val cleanPath = uriStr.replace(".java", "")
                val srcIndex = cleanPath.indexOf("/src/")
                if (srcIndex != -1) {
                  cleanPath.substring(srcIndex + 5).replace('/', '.')
                } else {
                  val lastSlash = cleanPath.lastIndexOf('/')
                  if (lastSlash != -1) cleanPath.substring(lastSlash + 1)
                  else cleanPath
                }
              case _ => super.inferBinaryName(location, file)
            }
          }
        }
      }

    val dependencySources = fetch.withClassifiers(Set("sources").asJava).fetch()
    dependencySources.asScala.foreach { jar =>
      index.addSourceJar(AbsolutePath(jar), dialect)
    }

    indexJdkSources()

    JavaPresentationCompiler()
      .withSearch(search(myclasspath))
      .withConfiguration(
        PresentationCompilerConfigImpl()
          .copy(
            isHoverDocumentationEnabled = documentationHoverEnabled,
            emitDiagnostics = true,
          )
      )
      .withLogger(LoggerFactory.getLogger("java.metals"))
      // TODO: we need a real instance of the embedded client here.
      .withEmbeddedClient(new TestingEmbeddedClient(tmp))
      .withJavaFileManagerFactory(javaFileManagerFactory)
      .newInstance("", myclasspath.asJava, Nil.asJava, () => Nil.asJava)
      .asInstanceOf[JavaPresentationCompiler]
  }

  override def params(
      code: String,
      filename: String = "test.java",
  ): (String, Int) = super.params(code, filename)

  override def hoverParams(
      code: String,
      filename: String = "test.scala",
  ): (String, Int, Int) = super.hoverParams(code, filename)

  protected def extraDependencies: Seq[Dependency] =
    Seq.empty

  private def getSourcePath(): Option[Path] = {
    val classLocation =
      getClass.getProtectionDomain.getCodeSource.getLocation.toURI
    val testClassesPath = Paths.get(classLocation).toAbsolutePath.toString
    val sep = File.separator
    val targetMarker = s"${sep}target${sep}"
    if (testClassesPath.contains(targetMarker)) {
      val projectRootStr =
        testClassesPath.substring(0, testClassesPath.lastIndexOf(targetMarker))
      val projectRoot = Paths.get(projectRootStr)
      val srcDir = projectRoot.resolve("testsources")
      if (srcDir.toFile.exists() && srcDir.toFile.isDirectory) {
        Option(srcDir)
      } else {
        None
      }
    } else {
      None
    }
  }

  class InMemoryJavaFile(uri: URI, val code: String)
      extends SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
    override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence =
      code
  }

  object ExternalSourceStorage {
    var fileObjects: List[JavaFileObject] = Nil
  }

  def registerExistingJavaFiles(sourcePath: Option[Path]): Unit = {
    sourcePath.foreach { rootPath =>
      if (Files.exists(rootPath) && Files.isDirectory(rootPath)) {
        Using(Files.walk(rootPath)) { stream =>
          val files = stream
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".java"))
            .toList

          ExternalSourceStorage.fileObjects = files.map { javaFile =>
            val code = Files.readString(javaFile, StandardCharsets.UTF_8)
            val absolutePath =
              scala.meta.io.AbsolutePath(javaFile.toAbsolutePath)
            val rootAbsolutePath =
              scala.meta.io.AbsolutePath(rootPath.toAbsolutePath)

            index.addSourceFile(absolutePath, Some(rootAbsolutePath), dialect)
            workspace.inputs(absolutePath.toURI.toString()) = (code, dialect)

            new InMemoryJavaFile(absolutePath.toURI, code)
          }
          scribe.info(
            s"Successfully pre-registered ${ExternalSourceStorage.fileObjects.size} Java file objects."
          )
        }.get
      }
    }
  }

}
