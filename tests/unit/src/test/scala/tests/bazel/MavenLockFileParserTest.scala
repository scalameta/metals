package tests.bazel

import java.nio.file.Files

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.ScannedExtDir
import scala.meta.internal.metals.mbt.importer.bazel.MavenLockFileParser
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.JsonObject
import scribe.Level
import scribe.LogRecord
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer
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

  test("wrapped-bzlmod-extension-bazel-8-and-bazel-7") {
    val workspaceRoot =
      AbsolutePath(Files.createTempDirectory("wrapped-bzlmod-test"))
    try {
      val outputBase = workspaceRoot.resolve("output_base")
      val extDir = outputBase.resolve("external")
      extDir.createDirectories()

      // Bazel 8+ root-wrapped extension (+maven_wrapper+...) with hub, binary jar, and sources jar
      val hubLock =
        extDir.resolve("+maven_wrapper+maven/imported_maven_install.json")
      val lockContents =
        """|{
           |  "artifacts": {
           |    "org.example.wrapped9025:alpha": {
           |      "shasums": { "jar": "aaa", "sources": "bbb" },
           |      "version": "1.2.3"
           |    },
           |    "org.example.wrapped9025:beta": {
           |      "shasums": { "jar": "ccc" },
           |      "version": "4.5.6-jre"
           |    },
           |    "org.example.wrapped9025:gamma": {
           |      "shasums": { "jar": "ddd" },
           |      "version": "0.1.0"
           |    }
           |  }
           |}
           |""".stripMargin
      hubLock.writeText(lockContents)

      val alphaJar = extDir.resolve(
        "+maven_wrapper+org_example_wrapped9025_alpha_1_2_3/file/v1/org/example/wrapped9025/alpha/1.2.3/alpha-1.2.3.jar"
      )
      val alphaSources = extDir.resolve(
        "+maven_wrapper+org_example_wrapped9025_alpha_sources_1_2_3/file/v1/org/example/wrapped9025/alpha/1.2.3/alpha-1.2.3-sources.jar"
      )
      // Non-root module wrapper (Bazel 8+): <module>+<version>+<ext>+<artifact>
      val betaJar = extDir.resolve(
        "my_mod+1.0.0+custom_maven+org_example_wrapped9025_beta_4_5_6_jre/file/v1/org/example/wrapped9025/beta/4.5.6-jre/beta-4.5.6-jre.jar"
      )
      // Bazel 7 root wrapper: _main~<ext>~<artifact>
      val gammaJar = extDir.resolve(
        "_main~maven_wrapper~org_example_wrapped9025_gamma_0_1_0/file/gamma-0.1.0.jar"
      )
      // Unrelated extension directory with same suffix but missing JAR should not shadow a valid one
      extDir
        .resolve("+other_ext+org_example_wrapped9025_alpha_1_2_3/file")
        .createDirectories()

      Seq(alphaJar, alphaSources, betaJar, gammaJar).foreach(
        _.writeBytes(Array.emptyByteArray)
      )

      val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(extDir))
      assertEquals(hubs.map(_.name.value), List("maven"))

      val modules = BazelMavenJsonImporter.importMaven(
        workspaceRoot,
        Some(outputBase),
        hubs,
      )
      assertEquals(
        modules,
        Seq(
          MbtDependencyModule(
            "org.example.wrapped9025:alpha:1.2.3",
            alphaJar.toURI.toString,
            alphaSources.toURI.toString,
          ),
          MbtDependencyModule(
            "org.example.wrapped9025:beta:4.5.6-jre",
            betaJar.toURI.toString,
            null,
          ),
          MbtDependencyModule(
            "org.example.wrapped9025:gamma:0.1.0",
            gammaJar.toURI.toString,
            null,
          ),
        ),
      )
    } finally {
      workspaceRoot.deleteRecursively()
    }
  }

  test("wrapped-bzlmod-unpinned-central-repo") {
    val workspaceRoot =
      AbsolutePath(Files.createTempDirectory("wrapped-unpinned-test"))
    try {
      val extDir = workspaceRoot.resolve("external")
      val jarFile = extDir.resolve(
        "+maven_wrapper+unpinned_maven/v1/https/repo1.maven.org/maven2/org/example/wrapped9025/central-only/2.0.0/central-only-2.0.0.jar"
      )
      jarFile.writeBytes(Array.emptyByteArray)

      val lockContents =
        """|{
           |  "artifacts": {
           |    "org.example.wrapped9025:central-only": {
           |      "version": "2.0.0"
           |    }
           |  }
           |}
           |""".stripMargin
      val jsonInput = gson.fromJson(lockContents, classOf[JsonObject])
      val parser = MavenLockFileParser.create(jsonInput)
      assert(parser.isDefined)

      val scanned = ScannedExtDir(extDir, extDir.list.toList)
      val output = parser.get.parse(
        Seq("maven"),
        Seq(scanned),
        Some("+maven_wrapper+"),
      )
      assertEquals(
        output,
        Seq(
          MbtDependencyModule(
            "org.example.wrapped9025:central-only:2.0.0",
            jarFile.dealias.toURI.toString,
            null,
          )
        ),
      )
    } finally {
      workspaceRoot.deleteRecursively()
    }
  }

  test("no-per-artifact-warn-flood-and-single-summary-warn") {
    val workspaceRoot =
      AbsolutePath(Files.createTempDirectory("warn-flood-test"))
    val previousLogger = scribe.Logger.root
    val warnings = ListBuffer.empty[String]
    val capturingWriter = new Writer {
      override def write(
          record: LogRecord,
          output: LogOutput,
          outputFormat: OutputFormat,
      ): Unit = {
        if (record.level.value >= Level.Warn.value) {
          warnings += output.plainText
        }
      }
    }
    try {
      val extDir = workspaceRoot.resolve("external")
      // Presence of a Bzlmod hub dir previously triggered per-artifact central repo warnings
      extDir.resolve("+maven_wrapper+maven").createDirectories()
      val presentJar = extDir.resolve(
        "+maven_wrapper+org_example_wrapped9025_no_sources_9999_0_empty/file/v1/org/example/wrapped9025/no-sources/9999.0-empty/no-sources-9999.0-empty.jar"
      )
      presentJar.writeBytes(Array.emptyByteArray)

      scribe.Logger.root
        .withHandler(writer = capturingWriter, minimumLevel = Some(Level.Warn))
        .replace()

      val scanned = ScannedExtDir(extDir, extDir.list.toList)

      // Case 1: binary JAR exists, sources JAR does not (e.g. listenablefuture) -> 0 warnings
      val resolvedJson = gson.fromJson(
        """|{
           |  "artifacts": {
           |    "org.example.wrapped9025:no-sources": {
           |      "version": "9999.0-empty"
           |    }
           |  }
           |}
           |""".stripMargin,
        classOf[JsonObject],
      )
      val resolvedOut = MavenLockFileParser
        .create(resolvedJson)
        .get
        .parse(Seq("maven"), Seq(scanned), Some("+maven_wrapper+"))
      assertEquals(resolvedOut.size, 1)
      assertEquals(warnings.toList, Nil)

      // Case 2: 3 missing binary JARs -> exactly 1 summary warning (not 2-4 per artifact)
      val missingJson = gson.fromJson(
        """|{
           |  "artifacts": {
           |    "org.example.nonexistent9025:missing-a": { "version": "0.0.1" },
           |    "org.example.nonexistent9025:missing-b": { "version": "0.0.1" },
           |    "org.example.nonexistent9025:missing-c": { "version": "0.0.1" }
           |  }
           |}
           |""".stripMargin,
        classOf[JsonObject],
      )
      val missingOut = MavenLockFileParser
        .create(missingJson)
        .get
        .parse(Seq("maven"), Seq(scanned), Some("+maven_wrapper+"))
      assertEquals(missingOut, Seq.empty)
      assertEquals(warnings.size, 1)
      assert(
        warnings.head.contains("3 Maven artifact JAR(s) not found locally"),
        s"Unexpected warning: ${warnings.head}",
      )
    } finally {
      previousLogger.replace()
      workspaceRoot.deleteRecursively()
    }
  }

  test("missing-and-dangling-per-artifact-repos") {
    val workspaceRoot =
      AbsolutePath(Files.createTempDirectory("dangling-repos-test"))
    try {
      val extDir = workspaceRoot.resolve("external")
      val cacheDir = workspaceRoot.resolve("repo_contents_cache/existing_entry")
      cacheDir.createDirectories()
      val evictedCacheTarget =
        workspaceRoot.resolve("repo_contents_cache/evicted_entry").toNIO

      val hubDir = extDir.resolve("+maven_wrapper+maven")
      hubDir.createDirectories()
      hubDir
        .resolve("imported_maven_install.json")
        .writeText(
          """|{
             |  "artifacts": {
             |    "io.grpc:grpc-okhttp": {
             |      "shasums": { "jar": "111", "sources": "222" },
             |      "version": "1.82.0"
             |    },
             |    "io.grpc:grpc-core": {
             |      "shasums": { "jar": "333", "sources": "444" },
             |      "version": "1.82.0"
             |    },
             |    "com.google.guava:listenablefuture": {
             |      "shasums": { "jar": "555" },
             |      "version": "9999.0-empty-to-avoid-conflict-with-guava"
             |    },
             |    "org.example:plus-build": {
             |      "shasums": { "jar": "888", "sources": "999" },
             |      "version": "1.0+build"
             |    },
             |    "org.unused:unused-lib": {
             |      "shasums": { "jar": "666", "sources": "777" },
             |      "version": "1.0.0"
             |    }
             |  },
             |  "dependencies": {
             |    "io.grpc:grpc-okhttp": [
             |      "io.grpc:grpc-core",
             |      "com.google.guava:listenablefuture",
             |      "org.example:plus-build"
             |    ]
             |  }
             |}
             |""".stripMargin
        )

      // grpc-okhttp binary repo: dangling symlink (GC-evicted from repo contents cache)
      val danglingOkhttp =
        extDir.resolve("+maven_wrapper+io_grpc_grpc_okhttp_1_82_0").toNIO
      Files.createSymbolicLink(danglingOkhttp, evictedCacheTarget)
      assert(
        Files.isSymbolicLink(danglingOkhttp) && !Files.exists(danglingOkhttp)
      )

      // grpc-core binary repo: valid symlink into existing cache entry
      Files.createSymbolicLink(
        extDir.resolve("+maven_wrapper+io_grpc_grpc_core_1_82_0").toNIO,
        cacheDir.toNIO,
      )
      // grpc-core sources repo: dangling symlink
      Files.createSymbolicLink(
        extDir.resolve("+maven_wrapper+io_grpc_grpc_core_sources_1_82_0").toNIO,
        evictedCacheTarget,
      )

      val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(extDir))
      val missing = BazelMavenJsonImporter.missingArtifactRepos(
        Set("@maven//:io_grpc_grpc_okhttp"),
        hubs,
      )

      assertEquals(
        missing,
        Set(
          "@@+maven_wrapper+io_grpc_grpc_okhttp_1_82_0",
          "@@+maven_wrapper+io_grpc_grpc_okhttp_sources_1_82_0",
          "@@+maven_wrapper+io_grpc_grpc_core_sources_1_82_0",
          "@@+maven_wrapper+com_google_guava_listenablefuture_9999_0_empty_to_avoid_conflict_with_guava",
          "@@+maven_wrapper+org_example_plus_build_1_0_build",
          "@@+maven_wrapper+org_example_plus_build_sources_1_0_build",
        ),
      )

      // Also verify hub BUILD filtering matches escaped '+' build metadata
      hubDir
        .resolve("BUILD")
        .writeText(
          """|jvm_import(
             |  name = "org_example_plus_build",
             |  jars = ["@@+maven_wrapper+org_example_plus_build_1_0_build//file"],
             |  srcjar = "@@+maven_wrapper+org_example_plus_build_jar_sources_1_0_build//file",
             |)
             |""".stripMargin
        )
      val missingWithBuild = BazelMavenJsonImporter.missingArtifactRepos(
        Set("@maven//:org_example_plus_build"),
        hubs,
      )
      assertEquals(
        missingWithBuild,
        Set(
          "@@+maven_wrapper+org_example_plus_build_1_0_build",
          "@@+maven_wrapper+org_example_plus_build_jar_sources_1_0_build",
        ),
      )
    } finally {
      workspaceRoot.deleteRecursively()
    }
  }

  test("preferred-extension-prefix-across-sources-variants") {
    val workspaceRoot =
      AbsolutePath(Files.createTempDirectory("preferred-sources-variant-test"))
    try {
      val extDir = workspaceRoot.resolve("external")
      val binaryJar = extDir.resolve(
        "+mine+org_example_alpha_1_0/file/v1/org/example/alpha/1.0/alpha-1.0.jar"
      )
      // Other extension uses the first naming variant (_sources_), while preferred
      // extension (+mine+) uses the second naming variant (_jar_sources_).
      val otherSources = extDir.resolve(
        "+other+org_example_alpha_sources_1_0/file/v1/org/example/alpha/1.0/alpha-1.0-sources.jar"
      )
      val mineJarSources = extDir.resolve(
        "+mine+org_example_alpha_jar_sources_1_0/file/v1/org/example/alpha/1.0/alpha-1.0-sources.jar"
      )
      Seq(binaryJar, otherSources, mineJarSources).foreach(
        _.writeBytes(Array.emptyByteArray)
      )

      val lockJson = gson.fromJson(
        """|{
           |  "artifacts": {
           |    "org.example:alpha": {
           |      "shasums": { "jar": "aaa", "sources": "bbb" },
           |      "version": "1.0"
           |    }
           |  }
           |}
           |""".stripMargin,
        classOf[JsonObject],
      )

      val scanned = ScannedExtDir(extDir, extDir.list.toList)
      val modules = MavenLockFileParser
        .create(lockJson)
        .get
        .parse(Seq("maven"), Seq(scanned), Some("+mine+"))

      assertEquals(
        modules,
        Seq(
          MbtDependencyModule(
            "org.example:alpha:1.0",
            binaryJar.toURI.toString,
            mineJarSources.toURI.toString,
          )
        ),
      )
    } finally {
      workspaceRoot.deleteRecursively()
    }
  }

}
