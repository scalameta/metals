package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.JavaInteractiveSemanticdb
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.JdkVersion
import scala.meta.internal.mtags.MtagsEnrichments.XtensionStringMtags
import scala.meta.io.AbsolutePath

import munit.FunSuite

class JavaInteractiveSemanticdbSuite extends FunSuite {

  private val javaBasePrefix: String =
    if (Properties.isJavaAtLeast("9")) "java.base/" else ""

  private val optJavaHome = JdkSources.defaultJavaHome(None).headOption
  private implicit val ctx: ExecutionContext = this.munitExecutionContext
  private val maybeJdkVersion: Option[JdkVersion] =
    JdkVersion.maybeJdkVersionFromJavaHome(optJavaHome)

  test("parse jdk-version") {
    assertEquals(JdkVersion.parse("17-ea"), Some(JdkVersion(17, "17-ea")))
    assertEquals(JdkVersion.parse("9"), Some(JdkVersion(9, "9")))
    assertEquals(
      JdkVersion.parse("1.8.0_312"),
      Some(JdkVersion(8, "1.8.0_312")),
    )
    assertEquals(JdkVersion.parse("11.0.13"), Some(JdkVersion(11, "11.0.13")))
  }

  test("compile jdk-class") {
    JdkSources(None) match {
      case Left(_) => fail("No JDK")
      case Right(jdkSource) =>
        val workspace = Files.createTempDirectory("metals")
        workspace.toFile().deleteOnExit()
        val buildTargets = BuildTargets.empty
        maybeJdkVersion match {
          case None => fail("No JDK Version")
          case Some(jdkVersion) =>
            val javaCompile = JavaInteractiveSemanticdb.create(
              AbsolutePath(workspace),
              buildTargets,
              jdkVersion,
            )
            val fileToCompile =
              s"jar:${jdkSource.toURI}!/${javaBasePrefix}java/nio/file/Path.java"
            val file = fileToCompile.toAbsolutePath
            val contents = FileIO.slurp(file, StandardCharsets.UTF_8)
            val output = javaCompile.textDocument(file, contents)
            // size varies per JDK version
            assert(output.symbols.nonEmpty)
            assert(output.occurrences.nonEmpty)
        }
    }
  }

  test("compile source") {
    val workspace = Files.createTempDirectory("metals")
    workspace.toFile().deleteOnExit()
    val buildTargets = BuildTargets.empty
    maybeJdkVersion match {
      case None => fail("No JDK Version")
      case Some(jdkVersion) =>
        val javaCompile = JavaInteractiveSemanticdb.create(
          AbsolutePath(workspace),
          buildTargets,
          jdkVersion,
        )
        val path = workspace.resolve("foo/bar/Main.java")
        Files.createDirectories(path.getParent)
        val contents = """package foo.bar;
                         |
                         |public class Main {
                         |  void hello()
                         |  {
                         |    System.out.println("Hello!");
                         |  }
                         |}""".stripMargin
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8))
        val output = javaCompile.textDocument(AbsolutePath(path), contents)
        assertEquals(output.symbols.size, 3)
        // size varies per JDK version
        assert(output.occurrences.nonEmpty)
    }
  }

  test("compile source-with-error") {
    val workspace = Files.createTempDirectory("metals")
    workspace.toFile().deleteOnExit()
    val buildTargets = BuildTargets.empty
    maybeJdkVersion match {
      case None => fail("No JDK Version")
      case Some(jdkVersion) =>
        val javaCompile = JavaInteractiveSemanticdb.create(
          AbsolutePath(workspace),
          buildTargets,
          jdkVersion,
        )
        val path = workspace.resolve("foo/bar/Main.java")
        Files.createDirectories(path.getParent)
        val contents = """package foo.bar;
                         |
                         |public class Main {
                         |  void hello()
                         |  {
                         |    System.out.println(foo);
                         |  }
                         |}""".stripMargin
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8))
        val output = javaCompile.textDocument(AbsolutePath(path), contents)
        assertEquals(output.symbols.size, 3)
        // size varies per JDK version
        assert(output.occurrences.nonEmpty)
    }
  }
}
