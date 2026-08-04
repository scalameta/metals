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
class PluginClasspathJavaVersionMismatchSuite
    extends BaseJavaPruneCompilerSuite {

  private def incompatiblePluginJar(): Path = {
    val jarPath = Files.createTempFile("incompatible-plugin", ".jar")
    val out = new ZipOutputStream(Files.newOutputStream(jarPath))
    out.putNextEntry(
      new ZipEntry("META-INF/services/com.sun.source.util.Plugin")
    )
    out.write("com.example.IncompatiblePlugin".getBytes("UTF-8"))
    out.closeEntry()
    out.putNextEntry(new ZipEntry("com/example/IncompatiblePlugin.class"))
    // 0xCAFEBABE identifies the file as a JVM class file (JVMS 4.1).
    val classFileMagicNumber =
      Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte)
    val minorVersion: Short = 0
    val majorVersionJava21: Short = 65
    // Just enough of a class file for the JVM to reject it while reading the
    // major version.
    out.write(classFileMagicNumber)
    out.write(Array[Byte]((minorVersion >> 8).toByte, minorVersion.toByte))
    out.write(
      Array[Byte]((majorVersionJava21 >> 8).toByte, majorVersionJava21.toByte)
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
