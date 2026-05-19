package tests.maven

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.meta.internal.metals.{BuildInfo => V}

import com.google.gson.JsonObject
import com.google.gson.JsonParser

class MavenMbtSuite extends munit.FunSuite {

  private val pluginGoal =
    s"org.scalameta:metals-maven-plugin:${V.metalsMavenPluginVersion}:export"

  test("compiler-options-from-pom") {
    val workspace = newWorkspace()
    writePom(workspace, "compiler-options.xml")
    mkdirs(workspace, "src/main/scala", "src/test/scala")
    val build = runExport(workspace)
    val main = namespace(build, "com.example:compiler-options:1.0.0")
    val test = namespace(build, "com.example:compiler-options:1.0.0:test")
    assertNoDiff(
      strings(main, "javacOptions").mkString("\n"),
      """|--release
         |11
         |-encoding
         |UTF-8
         |--enable-preview
         |-parameters
         |-proc:full
         |-processor
         |example.MainProcessor,example.SecondProcessor
         |-Xlint:deprecation
         |-ApluginConfig=top
         |-Werror
         |-Xdoclint:none
         |-Ametals:enabled
         |-verbose""".stripMargin,
    )
    assertNoDiff(
      strings(test, "javacOptions").mkString("\n"),
      """|-source
         |17
         |-target
         |17
         |-encoding
         |UTF-8
         |--enable-preview
         |-parameters
         |-proc:full
         |-processor
         |example.MainProcessor,example.SecondProcessor
         |-Xlint:deprecation
         |-ApluginConfig=top
         |-AtestExecution=true
         |-Werror
         |-Xdoclint:none
         |-Ametals:enabled
         |-verbose""".stripMargin,
    )
    assertNoDiff(
      strings(main, "scalacOptions").mkString("\n"),
      """|-Xlint
         |-deprecation
         |-feature""".stripMargin,
    )
    assertNoDiff(
      strings(test, "scalacOptions").mkString("\n"),
      """|-unchecked
         |-Wconf:any:warning-verbose
         |-Xsource:3""".stripMargin,
    )
    assertEquals(string(main, "scalaVersion"), Some(V.scala213))
    assertEquals(string(test, "scalaVersion"), Some("2.13"))
  }

  test("source-roots-from-pom") {
    val workspace = newWorkspace()
    writePom(workspace, "source-roots.xml")
    mkdirs(
      workspace,
      "src/main/java",
      "src/main/scala",
      "src/test/java",
      "src/test/scala",
      "src/generated/scala",
      "src/generated/extra-main",
      "src/generated-test/scala",
      "target/generated-sources/openapi/src/main/java/example",
      "target/generated-sources/plain/example",
      "target/generated-test-sources/spec/src/test/scala/example",
    )
    writeFile(workspace, "target/generated-sources/Top.java", "class Top {}")
    writeFile(
      workspace,
      "target/generated-sources/openapi/src/main/java/example/OpenApi.java",
      "package example; class OpenApi {}",
    )
    writeFile(
      workspace,
      "target/generated-sources/plain/example/Plain.scala",
      "package example\nobject Plain",
    )
    writeFile(
      workspace,
      "target/generated-test-sources/TopTest.java",
      "class TopTest {}",
    )
    writeFile(
      workspace,
      "target/generated-test-sources/spec/src/test/scala/example/SpecGenerated.scala",
      "package example\nclass SpecGenerated",
    )
    val build = runExport(workspace)
    val mainSources = relativeSources(
      workspace,
      namespace(build, "com.example:source-roots:1.0.0"),
    )
    val testSources = relativeSources(
      workspace,
      namespace(build, "com.example:source-roots:1.0.0:test"),
    )
    assertContainsAll(
      mainSources,
      List(
        "src/main/java", "src/main/scala",
        "target/generated-sources/antlr-custom", "src/generated/scala",
        "src/generated/extra-main", "target/generated-sources/apt-main",
        "target/generated-sources/protobuf-custom",
        "target/generated-sources/protobuf/grpc-java",
        "target/generated-sources",
        "target/generated-sources/openapi/src/main/java",
        "target/generated-sources/plain",
      ),
    )
    assertContainsAll(
      testSources,
      List(
        "src/test/java", "src/test/scala", "src/generated-test/scala",
        "target/generated-test-sources/apt-test",
        "target/generated-test-sources/protobuf-test-custom",
        "target/generated-test-sources/protobuf/grpc-java",
        "target/generated-test-sources",
        "target/generated-test-sources/spec/src/test/scala",
      ),
    )
    assert(mainSources.distinct == mainSources)
    assert(testSources.distinct == testSources)
  }

  test("java-only-pom") {
    val workspace = newJavaWorkspace("java-only")
    writePom(workspace, "java-only.xml")
    val build = runExport(workspace)
    assertNamespaceNames(
      build,
      Set("com.example:java-only:1.0.0", "com.example:java-only:1.0.0:test"),
    )
    val main = namespace(build, "com.example:java-only:1.0.0")
    val test = namespace(build, "com.example:java-only:1.0.0:test")
    assertEquals(strings(main, "javacOptions"), List("--release", "17"))
    assertEquals(
      strings(test, "dependsOn"),
      List("com.example:java-only:1.0.0"),
    )
    assert(main.get("scalaVersion").isJsonNull)
    assert(test.get("scalaVersion").isJsonNull)
  }

  test("forked-compiler-sets-java-home-per-execution") {
    val workspace = newJavaWorkspace("jdk-fork")
    writeJavaPom(
      workspace,
      "jdk-fork",
      compilerPlugin(executions =
        compileExecutions(
          mainConfig =
            """|<release>11</release>
               |<fork>true</fork>
               |<executable>${project.basedir}/jdk-main/bin/javac</executable>""".stripMargin,
          testConfig =
            """|<release>17</release>
               |<fork>true</fork>
               |<executable>${project.basedir}/jdk-test/bin/javac</executable>""".stripMargin,
        )
      ),
    )
    mkExecutable(workspace, "jdk-main/bin/javac")
    mkExecutable(workspace, "jdk-test/bin/javac")
    val build = runExport(workspace)
    val main = namespace(build, "com.example:jdk-fork:1.0.0")
    val test = namespace(build, "com.example:jdk-fork:1.0.0:test")
    assertJavaHome(workspace, main, "jdk-main")
    assertJavaHome(workspace, test, "jdk-test")
    assertEquals(strings(main, "javacOptions"), List("--release", "11"))
    assertEquals(strings(test, "javacOptions"), List("--release", "17"))
  }

  test("fork-executable-wins-over-jdk-toolchain") {
    val workspace = newJavaWorkspace("fork-beats-jdktoolchain")
    writeJavaPom(
      workspace,
      "fork-beats-jdktoolchain",
      compilerPlugin(configuration =
        """|<release>17</release>
           |<fork>true</fork>
           |<executable>${project.basedir}/jdk-17/bin/javac</executable>
           |<jdkToolchain><version>21</version></jdkToolchain>""".stripMargin
      ),
    )
    mkExecutable(workspace, "jdk-17/bin/javac")
    val tc = setupToolchains(workspace, ("21", None, "jdk-21"))
    val build = runExport(workspace, Some(tc))
    val main = namespace(build, "com.example:fork-beats-jdktoolchain:1.0.0")
    assertJavaHome(
      workspace,
      main,
      "jdk-17",
      "fork/executable must win over jdkToolchain",
    )
  }

  test("jdk-toolchain-in-compiler-plugin-selects-matching-jdk") {
    val workspace = newJavaWorkspace("jdktoolchain-compiler-plugin")
    writeJavaPom(
      workspace,
      "jdktoolchain-compiler-plugin",
      compilerPlugin(configuration =
        """|<release>21</release>
           |<jdkToolchain><version>21</version></jdkToolchain>""".stripMargin
      ),
    )
    val tc =
      setupToolchains(workspace, ("17", None, "jdk-17"), ("21", None, "jdk-21"))
    val build = runExport(workspace, Some(tc))
    val main =
      namespace(build, "com.example:jdktoolchain-compiler-plugin:1.0.0")
    assertJavaHome(workspace, main, "jdk-21")
    assert(strings(main, "javacOptions").contains("21"))
  }

  test("toolchains-plugin-config-wins-over-compiler-release") {
    val workspace = newJavaWorkspace("toolchains-plugin-jdk17")
    val pluginsXml =
      """|<plugin>
         |  <groupId>org.apache.maven.plugins</groupId>
         |  <artifactId>maven-toolchains-plugin</artifactId>
         |  <version>3.2.0</version>
         |  <configuration>
         |    <toolchains><jdk><version>17</version></jdk></toolchains>
         |  </configuration>
         |</plugin>
         |""".stripMargin +
        compilerPlugin(configuration = "<release>21</release>")
    writeJavaPom(workspace, "toolchains-plugin-jdk17", pluginsXml)
    val tc =
      setupToolchains(workspace, ("17", None, "jdk-17"), ("21", None, "jdk-21"))
    val build = runExport(workspace, Some(tc))
    val main = namespace(build, "com.example:toolchains-plugin-jdk17:1.0.0")
    assertJavaHome(workspace, main, "jdk-17")
  }

  test("per-execution-release-selects-distinct-toolchains-for-main-and-test") {
    val workspace = newJavaWorkspace("main-17-test-21")
    writeJavaPom(
      workspace,
      "main-17-test-21",
      compilerPlugin(executions =
        compileExecutions(
          mainConfig = "<release>17</release>",
          testConfig = "<release>21</release>",
        )
      ),
    )
    val tc =
      setupToolchains(workspace, ("17", None, "jdk-17"), ("21", None, "jdk-21"))
    val build = runExport(workspace, Some(tc))
    val main = namespace(build, "com.example:main-17-test-21:1.0.0")
    val test = namespace(build, "com.example:main-17-test-21:1.0.0:test")
    assertJavaHome(workspace, main, "jdk-17")
    assertJavaHome(workspace, test, "jdk-21")
    assert(strings(main, "javacOptions").contains("17"))
    assert(strings(test, "javacOptions").contains("21"))
  }

  test("jdk-toolchain-vendor-selects-matching-vendor") {
    val workspace = newJavaWorkspace("jdktoolchain-vendor-temurin")
    writeJavaPom(
      workspace,
      "jdktoolchain-vendor-temurin",
      compilerPlugin(configuration = """|<release>21</release>
                                        |<jdkToolchain>
                                        |  <version>21</version>
                                        |  <vendor>temurin</vendor>
                                        |</jdkToolchain>""".stripMargin),
    )
    val tc = setupToolchains(
      workspace,
      ("21", Some("temurin"), "jdk-temurin-21"),
      ("21", Some("oracle"), "jdk-oracle-21"),
    )
    val build = runExport(workspace, Some(tc))
    val main = namespace(build, "com.example:jdktoolchain-vendor-temurin:1.0.0")
    assertJavaHome(workspace, main, "jdk-temurin-21")
  }

  test("two-independent-build-roots-are-exported-without-cross-contamination") {
    val workspaceA = newWorkspace()
    writePom(workspaceA, "single-module.xml")
    mkdirs(workspaceA, "src/main/scala", "src/test/scala")

    val workspaceB = newJavaWorkspace("java-only")
    writePom(workspaceB, "java-only.xml")

    val buildA = runExport(workspaceA)
    val buildB = runExport(workspaceB)

    val depsA = dependencyIdsFrom(buildA)
    val depsB = dependencyIdsFrom(buildB)

    assert(
      depsA.exists(_.startsWith("org.scala-lang:scala-library:")),
      "root A (Scala project) must declare scala-library",
    )
    assert(
      !depsB.exists(_.startsWith("org.scala-lang:scala-library:")),
      "root B (Java-only project) must not contain scala-library from root A",
    )

    val sourcesA = relativeSources(
      workspaceA,
      namespace(buildA, "com.example:maven-mbt-test:1.0-SNAPSHOT"),
    )
    assert(
      sourcesA.forall(!_.startsWith(workspaceB.toString)),
      "root A source paths must not reference root B workspace",
    )
  }

  test("reactor-modules-from-pom") {
    val workspace = newWorkspace()
    writePom(workspace, "reactor-parent.xml")
    writeModulePom(workspace, "core", "reactor-core.xml")
    writeModulePom(workspace, "util", "reactor-util.xml")
    writeModulePom(workspace, "app", "reactor-app.xml")
    for (module <- List("core", "util", "app"))
      mkdirs(workspace, s"$module/src/main/scala", s"$module/src/test/scala")
    val build = runExport(workspace)
    assertNamespaceNames(
      build,
      Set(
        "com.example:core:1.0.0", "com.example:core:1.0.0:test",
        "com.example:util:1.0.0", "com.example:util:1.0.0:test",
        "com.example:app:1.0.0", "com.example:app:1.0.0:test",
      ),
    )
    assertEquals(
      strings(namespace(build, "com.example:core:1.0.0:test"), "dependsOn"),
      List("com.example:core:1.0.0"),
    )
    assertEquals(
      strings(namespace(build, "com.example:app:1.0.0"), "dependsOn"),
      List("com.example:core:1.0.0"),
    )
    assertEquals(
      strings(
        namespace(build, "com.example:app:1.0.0:test"),
        "dependsOn",
      ).toSet,
      Set(
        "com.example:app:1.0.0",
        "com.example:core:1.0.0",
        "com.example:core:1.0.0:test",
        "com.example:util:1.0.0",
      ),
    )
    val dependencyIds = dependencyIdsFrom(build)
    assert(dependencyIds.distinct == dependencyIds)
    assert(
      dependencyIds.contains(s"org.scala-lang:scala-library:${V.scala213}")
    )
  }

  private def newWorkspace(): Path =
    Files.createTempDirectory("maven-mbt-suite").toRealPath()

  private def newJavaWorkspace(name: String): Path = {
    val workspace = Files.createTempDirectory(s"maven-mbt-$name").toRealPath()
    mkdirs(workspace, "src/main/java", "src/test/java")
    workspace
  }

  private def writePom(workspace: Path, pomName: String): Unit =
    Files.writeString(workspace.resolve("pom.xml"), pom(pomName))

  private def writeJavaPom(
      workspace: Path,
      artifactId: String,
      pluginsXml: String,
  ): Unit =
    Files.writeString(
      workspace.resolve("pom.xml"),
      s"""|<project xmlns="http://maven.apache.org/POM/4.0.0"
          |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
          |  <modelVersion>4.0.0</modelVersion>
          |  <groupId>com.example</groupId>
          |  <artifactId>$artifactId</artifactId>
          |  <version>1.0.0</version>
          |  <build>
          |    <sourceDirectory>src/main/java</sourceDirectory>
          |    <testSourceDirectory>src/test/java</testSourceDirectory>
          |    <plugins>
          |$pluginsXml
          |    </plugins>
          |  </build>
          |</project>""".stripMargin,
    )

  private def writeModulePom(
      workspace: Path,
      module: String,
      pomName: String,
  ): Unit = {
    Files.createDirectories(workspace.resolve(module))
    Files.writeString(workspace.resolve(s"$module/pom.xml"), pom(pomName))
  }

  private def compilerPlugin(
      configuration: String = "",
      executions: String = "",
  ): String = {
    val configBlock =
      if (configuration.isEmpty) ""
      else s"<configuration>\n$configuration\n</configuration>\n"
    s"""|<plugin>
        |  <groupId>org.apache.maven.plugins</groupId>
        |  <artifactId>maven-compiler-plugin</artifactId>
        |  <version>3.13.0</version>
        |$configBlock$executions
        |</plugin>
        |""".stripMargin
  }

  private def compileExecutions(
      mainConfig: String,
      testConfig: String,
  ): String =
    s"""|<executions>
        |  <execution>
        |    <id>default-compile</id>
        |    <goals><goal>compile</goal></goals>
        |    <configuration>
        |$mainConfig
        |    </configuration>
        |  </execution>
        |  <execution>
        |    <id>default-testCompile</id>
        |    <goals><goal>testCompile</goal></goals>
        |    <configuration>
        |$testConfig
        |    </configuration>
        |  </execution>
        |</executions>""".stripMargin

  private def mkdirs(workspace: Path, paths: String*): Unit =
    paths.foreach(p => Files.createDirectories(workspace.resolve(p)))

  private def writeFile(
      workspace: Path,
      relPath: String,
      content: String,
  ): Unit = {
    val file = workspace.resolve(relPath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)
  }

  private def mkExecutable(workspace: Path, relPath: String): Unit = {
    val file = workspace.resolve(relPath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, "#!/bin/sh\n")
    file.toFile.setExecutable(true)
  }

  private lazy val mavenWrapperCommand: List[String] = {
    val tempDir = Files.createTempDirectory("metals-maven-wrapper")
    for (resource <- List("maven-wrapper.jar", "maven-wrapper.properties")) {
      val in = getClass.getResourceAsStream(s"/$resource")
      Files.copy(
        in,
        tempDir.resolve(resource),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    }
    List(
      scala.meta.internal.metals.JavaBinary(None),
      "-Dfile.encoding=UTF-8",
      s"-Dmaven.home=$tempDir",
      "-cp",
      tempDir.resolve("maven-wrapper.jar").toString,
      "org.apache.maven.wrapper.MavenWrapperMain",
    )
  }

  private def runExport(
      workspace: Path,
      toolchains: Option[Path] = None,
  ): JsonObject = {
    val outputFile = workspace.resolve("mbt.json")
    val toolchainsArgs =
      toolchains.toList.flatMap(tc => List("--toolchains", tc.toString))
    val command = mavenWrapperCommand :::
      List(
        s"-Dmaven.multiModuleProjectDirectory=$workspace",
        "--quiet",
        pluginGoal,
        s"-DmbtOutputFile=$outputFile",
      ) ::: toolchainsArgs
    val pb = new ProcessBuilder(command: _*)
      .directory(workspace.toFile)
      .inheritIO()
    val exitCode = pb.start().waitFor()
    assert(
      exitCode == 0,
      s"mvn export failed with exit code $exitCode in $workspace",
    )
    JsonParser.parseString(Files.readString(outputFile)).getAsJsonObject
  }

  private def fakeJdk(workspace: Path, name: String, version: String): Path = {
    val jdkDir = workspace.resolve(name)
    mkdirs(workspace, s"$name/bin")
    mkExecutable(workspace, s"$name/bin/java")
    mkExecutable(workspace, s"$name/bin/javac")
    Files.writeString(
      jdkDir.resolve("release"),
      s"""JAVA_VERSION="$version"\nIMPLEMENTOR="Test JDK"\n""",
    )
    jdkDir
  }

  private def setupToolchains(
      workspace: Path,
      entries: (String, Option[String], String)*
  ): Path = {
    val resolved = entries.toList.map { case (version, vendor, name) =>
      (version, vendor, fakeJdk(workspace, name, s"$version.0.1"))
    }
    writeToolchains(workspace.resolve("toolchains.xml"), resolved: _*)
  }

  private def writeToolchains(
      dest: Path,
      entries: (String, Option[String], Path)*
  ): Path = {
    val blocks = entries.map { case (version, vendor, path) =>
      val vendorXml =
        vendor.map(v => s"\n      <vendor>$v</vendor>").getOrElse("")
      s"""|  <toolchain>
          |    <type>jdk</type>
          |    <provides>
          |      <version>$version</version>$vendorXml
          |    </provides>
          |    <configuration><jdkHome>$path</jdkHome></configuration>
          |  </toolchain>""".stripMargin
    }
    val xml =
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">
          |${blocks.mkString("\n")}
          |</toolchains>""".stripMargin
    Files.writeString(dest, xml)
    dest
  }

  private def namespace(build: JsonObject, name: String): JsonObject = {
    val ns = build.getAsJsonObject("namespaces").get(name)
    assert(ns != null && ns.isJsonObject, s"missing namespace: $name")
    ns.getAsJsonObject
  }

  private def assertNamespaceNames(
      build: JsonObject,
      expected: Set[String],
  ): Unit = {
    val names = Set.newBuilder[String]
    build.getAsJsonObject("namespaces").keySet.forEach(names += _)
    assertEquals(names.result(), expected)
  }

  private def strings(obj: JsonObject, field: String): List[String] = {
    val result = List.newBuilder[String]
    obj.getAsJsonArray(field).forEach(e => result += e.getAsString)
    result.result()
  }

  private def string(obj: JsonObject, field: String): Option[String] =
    Option(obj.get(field)).filterNot(_.isJsonNull).map(_.getAsString)

  private def relativeSources(workspace: Path, ns: JsonObject): List[String] =
    strings(ns, "sources").map(toRelative(workspace, _))

  private def toRelative(workspace: Path, path: String): String =
    workspace.relativize(Path.of(path)).toString.replace('\\', '/')

  private def assertJavaHome(
      workspace: Path,
      ns: JsonObject,
      expected: String,
      hint: String = "",
  ): Unit = {
    val actual = string(ns, "javaHome").map(toRelative(workspace, _))
    val prefix = if (hint.isEmpty) "" else s"$hint: "
    assert(
      actual == Some(expected),
      s"${prefix}expected javaHome=$expected, got $actual",
    )
  }

  private def assertContainsAll(
      actual: List[String],
      expected: List[String],
  ): Unit = {
    val missing = expected.filterNot(actual.contains)
    assert(
      missing.isEmpty,
      s"missing: ${missing.mkString(", ")}; actual: ${actual.sorted.mkString(", ")}",
    )
  }

  private def dependencyIdsFrom(build: JsonObject): List[String] = {
    val result = List.newBuilder[String]
    build.getAsJsonArray("dependencyModules").forEach { e =>
      result += e.getAsJsonObject.get("id").getAsString
    }
    result.result()
  }

  private def pom(name: String): String = {
    val stream = getClass.getResourceAsStream(s"/maven/poms/$name")
    assert(
      stream != null,
      s"POM fixture not found on classpath: /maven/poms/$name",
    )
    new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      .replace("@@SCALA_VERSION@@", V.scala213)
  }
}
