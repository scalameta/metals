package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.ScannedExtDir
import scala.meta.internal.metals.mbt.importer.bazel.MavenLockFileParser
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.JsonObject
import tests.BaseSuite

class MavenLockFileParserTest extends BaseSuite {

  private val gson = new Gson()
  private var tmpDir: AbsolutePath = null

  override def beforeAll(): Unit = {
    tmpDir = AbsolutePath(Files.createTempDirectory("mock artifact repo "))
    tmpDir.resolve("external").createDirectories()
    Seq(
      "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_annotations_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-annotations/2.16.1/jackson-annotations-2.16.1.jar",
      "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_core_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-core/2.16.1/jackson-core-2.16.1.jar",
      "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_databind_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-databind/2.16.1/jackson-databind-2.16.1.jar",
    ).foreach { path =>
      tmpDir.resolve("external").resolve(path).writeBytes(Array.emptyByteArray)
    }
  }

  override def afterAll(): Unit = {
    Option(tmpDir).foreach(_.deleteRecursively())
  }

  test("parsing legacy lock file (up to version 4)") {
    // Excerpt from a real project.
    // Some fields are omitted for brevity, because the parser doesn't rely on them anyway.
    // https://github.com/wix-incubator/exodus/blob/dfb0c9713b07a8b6a49b548b7b543021e748d80b/maven_install.json#L142-L189
    val lockFileContents =
      """|{
         |  "dependency_tree": {
         |    "dependencies": [
         |      {
         |        "coord": "com.fasterxml.jackson.core:jackson-annotations:2.9.6",
         |        "file": "v1/https/repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar",
         |        "url": "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar"
         |      },
         |      {
         |        "coord": "com.fasterxml.jackson.core:jackson-core:2.9.6",
         |        "file": "v1/https/repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
         |        "url": "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar"
         |      },
         |      {
         |        "coord": "com.fasterxml.jackson.core:jackson-databind:2.9.6",
         |        "dependencies": [
         |          "com.fasterxml.jackson.core:jackson-annotations:2.9.6",
         |          "com.fasterxml.jackson.core:jackson-core:2.9.6"
         |        ],
         |        "directDependencies": [
         |          "com.fasterxml.jackson.core:jackson-annotations:2.9.6",
         |          "com.fasterxml.jackson.core:jackson-core:2.9.6"
         |        ],
         |        "file": "v1/https/repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar",
         |        "url": "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar"
         |      },
         |    ]
         |  }
         |}
         |""".stripMargin
    val jsonInput = gson.fromJson(lockFileContents, classOf[JsonObject])
    val parser = MavenLockFileParser.create(jsonInput)
    assert(
      parser.isDefined,
      "Could not find a matching parser for the lock file",
    )
    val repositoryNames = Seq("maven")
    val output = parser.get.parse(repositoryNames, Seq.empty)
    val expectedOutput = Seq(
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-annotations:2.9.6",
        userHome
          .resolve(
            ".m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar"
          )
          .toUri()
          .toString(),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-core:2.9.6",
        userHome
          .resolve(
            ".m2/repository/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar"
          )
          .toUri()
          .toString(),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-databind:2.9.6",
        userHome
          .resolve(
            ".m2/repository/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar"
          )
          .toUri()
          .toString(),
        null,
      ),
    )
    assertEquals(output, expectedOutput)
  }

  test("parsing version 5 lock file") {
    // Excerpt from a real project.
    // Some fields are omitted for brevity, because the parser doesn't rely on them anyway.
    // https://github.com/VirtusLab/bazel-steward/blob/bbe566eb97156b923df8d25c0e69ba469324b793/maven_install.json
    val lockFileContents =
      """|{
         |  "artifacts": {
         |    "com.fasterxml.jackson.core:jackson-annotations": {
         |      "version": "2.16.1"
         |    },
         |    "com.fasterxml.jackson.core:jackson-core": {
         |      "version": "2.16.1"
         |    },
         |    "com.fasterxml.jackson.core:jackson-databind": {
         |      "version": "2.16.1"
         |    }
         |  },
         |  "dependencies": {
         |    "com.fasterxml.jackson.core:jackson-databind": [
         |      "com.fasterxml.jackson.core:jackson-annotations",
         |      "com.fasterxml.jackson.core:jackson-core"
         |    ]
         |  }
         |}
         |""".stripMargin
    val jsonInput = gson.fromJson(lockFileContents, classOf[JsonObject])
    val parser = MavenLockFileParser.create(jsonInput)
    assert(
      parser.isDefined,
      "Could not find a matching parser for the lock file",
    )
    val repositoryNames = Seq("maven")
    val repoDir = tmpDir.resolve("external")
    val scannedRepoDirs = Seq(
      ScannedExtDir(
        dir = repoDir,
        entries = List(
          repoDir.resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_annotations_2_16_1"
          ),
          repoDir.resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_core_2_16_1"
          ),
          repoDir.resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_databind_2_16_1"
          ),
        ),
      )
    )
    val output = parser.get.parse(repositoryNames, scannedRepoDirs)
    val expectedOutput = Seq(
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-annotations:2.16.1",
        tmpDir
          .resolve("external")
          .resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_annotations_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-annotations/2.16.1/jackson-annotations-2.16.1.jar"
          )
          .toURI
          .toString(),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-core:2.16.1",
        tmpDir
          .resolve("external")
          .resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_core_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-core/2.16.1/jackson-core-2.16.1.jar"
          )
          .toURI
          .toString(),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-databind:2.16.1",
        tmpDir
          .resolve("external")
          .resolve(
            "rules_jvm_external~~maven~com_fasterxml_jackson_core_jackson_databind_2_16_1/file/v1/com/fasterxml/jackson/core/jackson-databind/2.16.1/jackson-databind-2.16.1.jar"
          )
          .toURI
          .toString(),
        null,
      ),
    )
    assertEquals(output, expectedOutput)
  }

}
