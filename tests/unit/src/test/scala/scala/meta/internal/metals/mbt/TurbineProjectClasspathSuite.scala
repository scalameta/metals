package scala.meta.internal.metals.mbt

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.{util => ju}
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

import scala.collection.parallel.mutable.ParArray
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.LoggerReportContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Sleeper
import scala.meta.internal.metals.config.TurbineRecompileDelayConfig
import scala.meta.pc.ProgressBars

import com.google.turbine.binder.ClassPathBinder
import com.google.turbine.diag.SourceFile
import munit.AnyFixture
import tests.TemporaryDirectoryFixture

class TurbineProjectClasspathSuite extends munit.FunSuite {
  private val tmp = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(tmp)
  implicit private val ec: ExecutionContext = ExecutionContext.global
  implicit private val rc: ReportContext = LoggerReportContext

  private case class FixtureJar(path: Path, classes: Map[String, Vector[Byte]])

  private def jar(
      name: String,
      method: String,
      withFallback: Boolean = false,
  ): FixtureJar = {
    val root = Files.createDirectories(tmp().toNIO.resolve(name))
    val output = Files.createDirectories(root.resolve("classes"))
    val sources = List(
      "Api" -> s"package fixture; public class Api { public static int $method() { return 1; } }"
    ) ++ (if (withFallback)
            List("OnlyGlobal" -> "package fixture; public class OnlyGlobal {}")
          else Nil)
    val paths = sources.map { case (className, text) =>
      val path = root.resolve(s"$className.java")
      Files.writeString(path, text)
      path.toString
    }
    val arguments = List("-proc:none", "-d", output.toString) ++ paths
    assertEquals(
      ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments: _*),
      0,
    )
    val classes = sources.map { case (className, _) =>
      s"fixture.$className" -> Files
        .readAllBytes(output.resolve(s"fixture/$className.class"))
        .toVector
    }.toMap
    val path = root.resolve(s"$name.jar")
    val archive = new JarOutputStream(Files.newOutputStream(path))
    try
      classes.toSeq.sortBy(_._1).foreach { case (binaryName, bytes) =>
        archive.putNextEntry(
          new JarEntry(binaryName.replace('.', '/') + ".class")
        )
        archive.write(bytes.toArray)
        archive.closeEntry()
      }
    finally archive.close()
    FixtureJar(path, classes)
  }

  private def compiler(
      inputs: Seq[Path],
      resultClasspath: Seq[Path] = Nil,
  ): TurbineCompiler[Unit] = {
    val compiler = new TurbineCompiler[Unit](
      allCompilationUnits = () => ParArray.empty[Unit],
      parseUnit = _ => Seq.empty[SourceFile],
      classpath = () => inputs,
      progressBars = ProgressBars.EMPTY,
      turbineRecompileDelay = () => TurbineRecompileDelayConfig.testing,
      listProtoJavaOutlinesForPackage = _ => Iterator.empty,
      sleeper = Sleeper.TestingSleeper,
      onIndexingDone = () => (),
      onNewProjectClasspath = _ => (),
    )
    compiler.result = TurbineCompiler.emptyResult.copy(
      classpath = ClassPathBinder.bindClasspath(resultClasspath.asJava)
    )
    compiler
  }

  private def listedClasses(
      compiler: TurbineCompiler[Unit],
      targetClasspath: Seq[Path],
  ): Map[String, Vector[Byte]] = {
    val standard = ToolProvider
      .getSystemJavaCompiler()
      .getStandardFileManager(null, null, null)
    val manager = compiler.createFileManager(standard, targetClasspath.asJava)
    try {
      val classes = manager
        .list(
          StandardLocation.CLASS_PATH,
          "fixture",
          ju.EnumSet.of(JavaFileObject.Kind.CLASS),
          false,
        )
        .asScala
        .toVector
      val grouped = classes.groupBy(file =>
        manager.inferBinaryName(StandardLocation.CLASS_PATH, file)
      )
      grouped.map { case (name, entries) =>
        assertEquals(entries.size, 1, clues(name))
        val stream = entries.head.openInputStream()
        try name -> stream.readAllBytes().toVector
        finally stream.close()
      }
    } finally manager.close()
  }

  test("target-jar-is-available-before-global-compilation") {
    val target = jar("target", "current")
    assertEquals(
      listedClasses(compiler(Seq(target.path)), Seq(target.path)),
      target.classes,
    )
  }

  test("shared-target-and-global-jar-has-no-duplicate-classes") {
    val target = jar("target", "current")
    assertEquals(
      listedClasses(
        compiler(Seq(target.path), Seq(target.path)),
        Seq(target.path),
      ),
      target.classes,
    )
  }

  test("target-version-wins-over-stale-global-result-with-fallback-retained") {
    val stale = jar("stale", "oldVersion", withFallback = true)
    val target = jar("target", "newVersion")
    val obtained = listedClasses(
      compiler(Seq(target.path), Seq(stale.path)),
      Seq(target.path),
    )
    assertEquals(obtained, stale.classes ++ target.classes)
  }

  test("each-target-keeps-its-own-version") {
    val first = jar("first", "firstVersion")
    val second = jar("second", "secondVersion")
    val global = compiler(Seq(first.path, second.path), Seq(first.path))
    assertEquals(listedClasses(global, Seq(first.path)), first.classes)
    assertEquals(listedClasses(global, Seq(second.path)), second.classes)
  }

  test("global-result-remains-a-fallback-without-target-jars") {
    val global = jar("global", "fallback")
    assertEquals(
      listedClasses(compiler(Seq(global.path), Seq(global.path)), Nil),
      global.classes,
    )
  }

  test("invalid-target-entries-are-ignored") {
    val target = jar("target", "current")
    val notJar = tmp().toNIO.resolve("notes.txt")
    Files.writeString(notJar, "not a jar")
    val directory = Files.createDirectory(tmp().toNIO.resolve("directory.jar"))
    assertEquals(
      listedClasses(
        compiler(Seq(target.path)),
        Seq(notJar, directory, tmp().toNIO.resolve("missing.jar"), target.path),
      ),
      target.classes,
    )
  }
}
