package tests.j

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression test for a crash where the presentation compiler's own
 * `-Xplugin:MetalsHeaderCompiler` plugin failed to load whenever the build
 * target's classpath contained a jar registering an unrelated
 * `com.sun.source.util.Plugin` service provider compiled for a newer JDK.
 *
 * javac's Plugin ServiceLoader lookup must load every provider class on the
 * relevant path to read its name, so a single incompatible jar anywhere on
 * that path crashes plugin discovery before MetalsHeaderCompiler is ever
 * found (see JavacProcessingEnvironment.getProcessorClassLoader, which falls
 * back to scanning `-classpath` whenever `-processorpath` isn't set).
 */
class JavaPruneCompilerPluginClasspathSuite extends BaseJavaPruneCompilerSuite {

  private def incompatiblePluginJar(): Path = {
    val jarPath = Files.createTempFile("incompatible-plugin", ".jar")
    val out = new ZipOutputStream(Files.newOutputStream(jarPath))
    out.putNextEntry(
      new ZipEntry("META-INF/services/com.sun.source.util.Plugin")
    )
    out.write("com.example.IncompatiblePlugin".getBytes("UTF-8"))
    out.closeEntry()
    out.putNextEntry(new ZipEntry("com/example/IncompatiblePlugin.class"))
    // Just enough of a class file for the JVM to reject it while reading the
    // major version: magic number, minor version 0, major version 65 (Java 21).
    out.write(
      Array[Byte](
        0xca.toByte,
        0xfe.toByte,
        0xba.toByte,
        0xbe.toByte,
        0,
        0,
        0,
        65,
      )
    )
    out.closeEntry()
    out.close()
    jarPath
  }

  checkNoErrors(
    "classpath-with-incompatible-plugin-jar",
    """|/foo/Example.java
       |package foo;
       |public class Example {
       |  public static final String greeting = "hello";
       |}
       |""".stripMargin,
    "foo/Example.java",
    classpath = List(incompatiblePluginJar()),
  )
}
