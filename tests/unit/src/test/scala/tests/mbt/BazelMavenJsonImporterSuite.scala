package tests.mbt

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.io.AbsolutePath

class BazelMavenJsonImporterSuite extends tests.BaseSuite {

  private def createJarWithProcessorService(
      dir: java.nio.file.Path,
      name: String,
      processorClasses: Seq[String],
  ): java.nio.file.Path = {
    val jar = dir.resolve(name)
    val zos = new ZipOutputStream(Files.newOutputStream(jar))
    try {
      zos.putNextEntry(
        new ZipEntry("META-INF/services/javax.annotation.processing.Processor")
      )
      zos.write(processorClasses.mkString("\n").getBytes("UTF-8"))
      zos.closeEntry()
    } finally zos.close()
    jar
  }

  private def createEmptyJar(
      dir: java.nio.file.Path,
      name: String,
  ): java.nio.file.Path = {
    val jar = dir.resolve(name)
    val zos = new ZipOutputStream(Files.newOutputStream(jar))
    zos.close()
    jar
  }

  test("annotation-processors-from-service-file") {
    val dir = Files.createTempDirectory("bazel-maven-importer")
    val lombokJar = createJarWithProcessorService(
      dir,
      "lombok-1.18.34.jar",
      Seq("lombok.launch.AnnotationProcessorHider$AnnotationProcessor"),
    )

    val mavenInstallJson =
      s"""|{
          |  "artifacts": {
          |    "org.projectlombok:lombok": {
          |      "version": "1.18.34",
          |      "shasums": {}
          |    }
          |  }
          |}
          |""".stripMargin

    val lockFile = dir.resolve("maven_install.json")
    Files.writeString(lockFile, mavenInstallJson)

    // Place the jar where the importer can find it (local m2 cache path)
    val m2Dir = dir.resolve(
      "m2/org/projectlombok/lombok/1.18.34"
    )
    Files.createDirectories(m2Dir)
    Files.copy(lombokJar, m2Dir.resolve("lombok-1.18.34.jar"))

    // Override user.home to point at our temp directory
    val savedHome = System.getProperty("user.home")
    System.setProperty("user.home", dir.toAbsolutePath.toString + "/m2_home")

    // Create m2 repository structure under the mocked home
    val m2RepoDir =
      dir.resolve("m2_home/.m2/repository/org/projectlombok/lombok/1.18.34")
    Files.createDirectories(m2RepoDir)
    Files.copy(lombokJar, m2RepoDir.resolve("lombok-1.18.34.jar"))

    try {
      val modules = BazelMavenJsonImporter.importMaven(
        AbsolutePath(dir),
        outputBase = None,
        repositoryName = "maven",
      )

      val lombokModule =
        modules.find(_.id == "org.projectlombok:lombok:1.18.34")
      assert(clue(lombokModule).isDefined)
      assertEquals(
        lombokModule.get.getAnnotationProcessors.asScala.toList,
        List("lombok.launch.AnnotationProcessorHider$AnnotationProcessor"),
      )
    } finally {
      System.setProperty("user.home", savedHome)
    }
  }

  test("no-annotation-processors-for-plain-jar") {
    val dir = Files.createTempDirectory("bazel-maven-importer-plain")
    val guavaJar = createEmptyJar(dir, "guava-32.0.0.jar")

    val mavenInstallJson =
      s"""|{
          |  "artifacts": {
          |    "com.google.guava:guava": {
          |      "version": "32.0.0",
          |      "shasums": {}
          |    }
          |  }
          |}
          |""".stripMargin

    val lockFile = dir.resolve("maven_install.json")
    Files.writeString(lockFile, mavenInstallJson)

    val savedHome = System.getProperty("user.home")
    val m2RepoDir =
      dir.resolve(".m2_home/.m2/repository/com/google/guava/guava/32.0.0")
    Files.createDirectories(m2RepoDir)
    Files.copy(guavaJar, m2RepoDir.resolve("guava-32.0.0.jar"))
    System.setProperty(
      "user.home",
      dir.resolve(".m2_home").toAbsolutePath.toString,
    )

    try {
      val modules = BazelMavenJsonImporter.importMaven(
        AbsolutePath(dir),
        outputBase = None,
        repositoryName = "maven",
      )

      val guavaModule = modules.find(_.id == "com.google.guava:guava:32.0.0")
      assert(clue(guavaModule).isDefined)
      assert(guavaModule.get.getAnnotationProcessors.isEmpty)
      assert(guavaModule.get.annotationProcessors == null)
    } finally {
      System.setProperty("user.home", savedHome)
    }
  }
}
