package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.bazel.MavenLockFileParser
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.JsonObject
import tests.BaseSuite

class MavenLockFileParserTest extends BaseSuite {

  private val gson = new Gson()
  private var tmpDir: Option[AbsolutePath] = None

  override def beforeAll(): Unit = {
    val tmp = AbsolutePath(Files.createTempDirectory("mock artifact repo "))
    tmpDir = Some(tmp)
  }

  override def afterAll(): Unit = {
    tmpDir.foreach(_.deleteRecursively())
  }

  private def toUriString(relativePath: String) =
    userHome.resolve(relativePath).toUri().toString()

  test("parsing legacy lock file (up to version 4)") {
    // Excerpt from a real project
    // https://github.com/wix-incubator/exodus/blob/dfb0c9713b07a8b6a49b548b7b543021e748d80b/maven_install.json#L142-L189
    val lockFileContents =
      """|{
         |  "dependency_tree": {
         |    "dependencies": [
         |      {
         |        "coord": "com.fasterxml.jackson.core:jackson-annotations:2.9.6",
         |        "dependencies": [],
         |        "directDependencies": [],
         |        "file": "v1/https/repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar",
         |        "mirror_urls": [
         |          "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar",
         |          "https://mvnrepository.com/artifact/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar",
         |          "https://maven-central.storage.googleapis.com/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar",
         |          "http://gitblit.github.io/gitblit-maven/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar"
         |        ],
         |        "sha256": "4d1ce5575ad53bee8caae4c15016878e2c3ea47276e675a35ea6bdde3bb0e653",
         |        "url": "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar"
         |      },
         |      {
         |        "coord": "com.fasterxml.jackson.core:jackson-core:2.9.6",
         |        "dependencies": [],
         |        "directDependencies": [],
         |        "file": "v1/https/repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
         |        "mirror_urls": [
         |          "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
         |          "https://mvnrepository.com/artifact/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
         |          "https://maven-central.storage.googleapis.com/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
         |          "http://gitblit.github.io/gitblit-maven/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar"
         |        ],
         |        "sha256": "fab8746aedd6427788ee390ea04d438ec141bff7eb3476f8bdd5d9110fb2718a",
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
         |        "mirror_urls": [
         |          "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar",
         |          "https://mvnrepository.com/artifact/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar",
         |          "https://maven-central.storage.googleapis.com/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar",
         |          "http://gitblit.github.io/gitblit-maven/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar"
         |        ],
         |        "sha256": "657e3e979446d61f88432b9c50f0ccd9c1fe4f1c822d533f5572e4c0d172a125",
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
        toUriString(
          ".m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.9.6/jackson-annotations-2.9.6.jar"
        ),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-core:2.9.6",
        toUriString(
          ".m2/repository/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar"
        ),
        null,
      ),
      MbtDependencyModule(
        "com.fasterxml.jackson.core:jackson-databind:2.9.6",
        toUriString(
          ".m2/repository/com/fasterxml/jackson/core/jackson-databind/2.9.6/jackson-databind-2.9.6.jar"
        ),
        null,
      ),
    )
    assertEquals(output, expectedOutput)
  }

  test("parsing version 5 lock file") {
    // Excerpt from a real project
    // https://github.com/VirtusLab/bazel-steward/blob/bbe566eb97156b923df8d25c0e69ba469324b793/maven_install.json
    val lockFileContents =
      """|{
         |  "artifacts": {
         |    "com.fasterxml.jackson.core:jackson-annotations": {
         |      "shasums": {
         |        "jar": "a4730771e6a495dd3793a42cdb8ce6bddb96c77e15f40c98fd8d9a7ae09e7286",
         |        "sources": "cebdb714198d5b3a152efb9e940dc9eb26cb37aa688ce34431b99afb790f6be3"
         |      },
         |      "version": "2.16.1"
         |    },
         |    "com.fasterxml.jackson.core:jackson-core": {
         |      "shasums": {
         |        "jar": "f5f8ef90609e64fec82eb908e497dc7d81b2eb983fe509b870292a193cde4dfb",
         |        "sources": "1bd334b0de7d02d7f8a6591f775a28126b1cdce9cd9ae6dc260482b7bdd9a04c"
         |      },
         |      "version": "2.16.1"
         |    },
         |    "com.fasterxml.jackson.core:jackson-databind": {
         |      "shasums": {
         |        "jar": "baf8a8ebee8f45ef68cdd5e2dd3923b3e296c0937b96ec0b4806aa3a31bccd1d",
         |        "sources": "91390204018cbeb9c5ed6e60c2624b63c8221082af9b0e94ce7f2d926ec48e2c"
         |      },
         |      "version": "2.16.1"
         |    }
         |  },
         |  "dependencies": {
         |    "com.fasterxml.jackson.core:jackson-databind": [
         |      "com.fasterxml.jackson.core:jackson-annotations",
         |      "com.fasterxml.jackson.core:jackson-core"
         |    ]
         |  },
         |  "repositories": {
         |    "https://repo.maven.apache.org/maven2/": [
         |      "com.fasterxml.jackson.core:jackson-annotations",
         |      "com.fasterxml.jackson.core:jackson-annotations:jar:sources",
         |      "com.fasterxml.jackson.core:jackson-core",
         |      "com.fasterxml.jackson.core:jackson-core:jar:sources",
         |      "com.fasterxml.jackson.core:jackson-databind",
         |      "com.fasterxml.jackson.core:jackson-databind:jar:sources"
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
    // TODO set up the scanned directories and assert that dependency modules are created correctly
  }

}
