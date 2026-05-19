package scala.meta.metals.maven

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections

import scala.jdk.CollectionConverters._

import com.google.gson.Gson
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.execution.DefaultMavenExecutionRequest
import org.apache.maven.execution.MavenSession
import org.apache.maven.execution.ProjectDependencyGraph
import org.apache.maven.model.Build
import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.logging.SystemStreamLog
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectBuilder
import org.apache.maven.project.ProjectBuildingResult
import org.apache.maven.toolchain.ToolchainManager
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResult
class MbtMojoImplSuite extends munit.FunSuite {

  test(
    "resolved SNAPSHOT dependency is found in local repo despite timestamp version"
  ) {
    val workspace = Files.createTempDirectory("mbt-snapshot-dep")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    val baseVersionDir = localRepo.resolve("org/example/core/1.0.0-SNAPSHOT")
    Files.createDirectories(baseVersionDir)
    val timestampedJar =
      baseVersionDir.resolve("core-1.0.0-20240101.120000-1.jar")
    Files.createFile(timestampedJar)

    val app = project("app", workspace.resolve("app").toFile)
    val snapshotArtifact = new DefaultArtifact(
      "org.example",
      "core",
      "1.0.0-20240101.120000-1",
      Artifact.SCOPE_COMPILE,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )
    app.setArtifacts(Set[Artifact](snapshotArtifact).asJava)

    MbtMojoImpl.run(new TestMojo(List(app), output.toFile, localRepo.toFile))

    val build =
      new Gson().fromJson(Files.readString(output), classOf[MbtBuildJson])
    val depIds = build.dependencyModules.asScala.map(_.id).toSet
    assert(
      depIds.contains("org.example:core:1.0.0-20240101.120000-1"),
      s"Expected snapshot dep in output, got: $depIds",
    )
  }

  test("external jar dependency appears in dependency modules") {
    val workspace = Files.createTempDirectory("mbt-ext-dep")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    val jarPath =
      localRepo.resolve("com/example/utils/2.0.0/utils-2.0.0.jar")
    Files.createDirectories(jarPath.getParent)
    Files.createFile(jarPath)

    val app = project("app", workspace.resolve("app").toFile)
    val extArtifact = new DefaultArtifact(
      "com.example",
      "utils",
      "2.0.0",
      Artifact.SCOPE_COMPILE,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )
    extArtifact.setFile(jarPath.toFile)
    app.setArtifacts(Set[Artifact](extArtifact).asJava)

    MbtMojoImpl.run(new TestMojo(List(app), output.toFile, localRepo.toFile))

    val build =
      new Gson().fromJson(Files.readString(output), classOf[MbtBuildJson])
    val depIds =
      build.dependencyModules.asScala.map(_.id).toSet
    val mainDeps =
      build.namespaces
        .asScala("com.example:app:1.0.0")
        .dependencyModules
        .asScala
        .toList

    assert(depIds.contains("com.example:utils:2.0.0"))
    assert(mainDeps.contains("com.example:utils:2.0.0"))
  }

  test(
    "declared dependencies are collected when Maven artifacts are unavailable"
  ) {
    val workspace = Files.createTempDirectory("mbt-declared-deps")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    createLocalJar(localRepo.toFile, "org.libs", "direct", "1.0.0")
    createLocalSourcesJar(localRepo.toFile, "org.libs", "direct", "1.0.0")
    createLocalJar(localRepo.toFile, "org.libs", "default-scope", "1.0.0")
    createLocalJar(localRepo.toFile, "org.libs", "provided", "1.0.0")
    createLocalJar(localRepo.toFile, "org.libs", "system", "1.0.0")
    createLocalJar(localRepo.toFile, "org.libs", "runtime", "1.0.0")
    createLocalJar(localRepo.toFile, "org.libs", "test-only", "1.0.0")
    createLocalJar(
      localRepo.toFile,
      "org.libs",
      "classified",
      "1.0.0",
      Some("shaded"),
    )
    createLocalJar(
      localRepo.toFile,
      "org.libs",
      "fixtures",
      "1.0.0",
      Some("tests"),
    )
    createLocalJar(localRepo.toFile, "org.libs", "managed", "3.0.0")

    val app = project("app", workspace.resolve("app").toFile)
    app.setDependencies(
      List(
        dependency("direct", "1.0.0", Artifact.SCOPE_COMPILE),
        dependency("default-scope", "1.0.0", null),
        dependency("provided", "1.0.0", Artifact.SCOPE_PROVIDED),
        dependency("system", "1.0.0", Artifact.SCOPE_SYSTEM),
        dependency("runtime", "1.0.0", Artifact.SCOPE_RUNTIME),
        dependency("test-only", "1.0.0", Artifact.SCOPE_TEST),
        dependency(
          "classified",
          "1.0.0",
          Artifact.SCOPE_COMPILE,
          classifier = Some("shaded"),
        ),
        dependency(
          "fixtures",
          "1.0.0",
          Artifact.SCOPE_TEST,
          tpe = "test-jar",
        ),
        dependency("managed", null, Artifact.SCOPE_COMPILE),
        dependency("pom-only", "1.0.0", Artifact.SCOPE_COMPILE, tpe = "pom"),
      ).asJava
    )
    app.setManagedVersionMap(
      Map[String, Artifact](
        "org.libs:managed:jar" ->
          new DefaultArtifact(
            "org.libs",
            "managed",
            "3.0.0",
            Artifact.SCOPE_COMPILE,
            "jar",
            null,
            new DefaultArtifactHandler("jar"),
          )
      ).asJava
    )

    MbtMojoImpl.run(new TestMojo(List(app), output.toFile, localRepo.toFile))

    val build = readBuild(output.toFile)
    val modulesById = build.dependencyModules.asScala.map(m => m.id -> m).toMap
    val mainDeps =
      build.namespaces
        .asScala("com.example:app:1.0.0")
        .dependencyModules
        .asScala
        .toSet
    val testDeps =
      build.namespaces
        .asScala("com.example:app:1.0.0:test")
        .dependencyModules
        .asScala
        .toSet

    assertNoDiff(
      mainDeps.toList.sorted.mkString("\n"),
      """|org.libs:classified:shaded:1.0.0
         |org.libs:default-scope:1.0.0
         |org.libs:direct:1.0.0
         |org.libs:managed:3.0.0
         |org.libs:provided:1.0.0
         |org.libs:system:1.0.0""".stripMargin,
    )
    assertNoDiff(
      testDeps.toList.sorted.mkString("\n"),
      """|org.libs:classified:shaded:1.0.0
         |org.libs:default-scope:1.0.0
         |org.libs:direct:1.0.0
         |org.libs:fixtures:tests:1.0.0
         |org.libs:managed:3.0.0
         |org.libs:provided:1.0.0
         |org.libs:runtime:1.0.0
         |org.libs:system:1.0.0
         |org.libs:test-only:1.0.0""".stripMargin,
    )
    assert(!modulesById.contains("org.libs:pom-only:1.0.0"))
    assert(modulesById("org.libs:direct:1.0.0").sources != null)
  }

  test("downloadSources attaches sources resolved through repository system") {
    val workspace = Files.createTempDirectory("mbt-download-sources")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    createLocalJar(localRepo.toFile, "com.example", "utils", "2.0.0")
    val sources = Files
      .createDirectories(workspace.resolve("remote"))
      .resolve("utils-2.0.0-sources.jar")
    Files.createFile(sources)

    val app = project("app", workspace.resolve("app").toFile)
    val extArtifact = new DefaultArtifact(
      "com.example",
      "utils",
      "2.0.0",
      Artifact.SCOPE_COMPILE,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )
    app.setArtifacts(Set[Artifact](extArtifact).asJava)

    val session = new MavenSession(
      null,
      null.asInstanceOf[RepositorySystemSession],
      new DefaultMavenExecutionRequest(),
      null,
    )
    session.setCurrentProject(app)
    MbtMojoImpl.run(
      new TestMojo(
        List(app),
        output.toFile,
        localRepo.toFile,
        downloadSources = true,
        repoSystem = repoSystemResolving(
          Map("com.example:utils:2.0.0" -> sources.toFile)
        ),
        session = session,
      )
    )

    val build = readBuild(output.toFile)
    val module = build.dependencyModules.asScala
      .find(_.id == "com.example:utils:2.0.0")
      .get

    assertEquals(module.sources, sources.toFile.toURI.toString)
  }

  test("output file is written even when parent directories do not exist") {
    val workspace = Files.createTempDirectory("mbt-nested-output")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("a/b/c/mbt.json")

    val app = project("app", workspace.resolve("app").toFile)
    MbtMojoImpl.run(new TestMojo(List(app), output.toFile, localRepo.toFile))

    assert(
      Files.exists(output),
      s"expected output file to be created at $output",
    )
  }

  test(
    "duplicate external dependency is emitted only once in dependency modules"
  ) {
    val workspace = Files.createTempDirectory("mbt-dedup-deps")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    createLocalJar(localRepo.toFile, "com.example", "shared", "1.0.0")

    val app = project("app", workspace.resolve("app").toFile)
    val sharedArtifact = new DefaultArtifact(
      "com.example",
      "shared",
      "1.0.0",
      Artifact.SCOPE_COMPILE,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )
    sharedArtifact.setFile(
      localRepo
        .resolve("com/example/shared/1.0.0/shared-1.0.0.jar")
        .toFile
    )
    app.setArtifacts(Set[Artifact](sharedArtifact, sharedArtifact).asJava)

    MbtMojoImpl.run(new TestMojo(List(app), output.toFile, localRepo.toFile))

    val build = readBuild(output.toFile)
    val depIds = build.dependencyModules.asScala.map(_.id).toList

    assert(
      depIds.count(_ == "com.example:shared:1.0.0") == 1,
      s"expected exactly one entry for shared, got: $depIds",
    )
  }

  test("artifact with missing file is silently skipped in dependency modules") {
    val workspace = Files.createTempDirectory("mbt-missing-jar")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    val app = project("app", workspace.resolve("app").toFile)
    val missingArtifact = new DefaultArtifact(
      "com.example",
      "ghost",
      "1.0.0",
      Artifact.SCOPE_COMPILE,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )
    missingArtifact.setFile(workspace.resolve("nonexistent.jar").toFile)
    app.setArtifacts(Set[Artifact](missingArtifact).asJava)

    val session = new MavenSession(
      null,
      null.asInstanceOf[RepositorySystemSession],
      new DefaultMavenExecutionRequest(),
      null,
    )
    session.setCurrentProject(app)
    MbtMojoImpl.run(
      new TestMojo(
        List(app),
        output.toFile,
        localRepo.toFile,
        session = session,
      )
    )

    val build = readBuild(output.toFile)
    assert(
      !build.dependencyModules.asScala
        .map(_.id)
        .toSet
        .contains("com.example:ghost:1.0.0"),
      "missing artifact must not appear in dependencyModules",
    )
    assert(
      build.namespaces
        .asScala("com.example:app:1.0.0")
        .dependencyModules
        .asScala
        .isEmpty,
      "namespace must not reference missing artifact",
    )
  }

  test(
    "artifact with existing non-jar file is not registered and not referenced in namespace"
  ) {
    val workspace = Files.createTempDirectory("mbt-non-jar")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    val pomFile = workspace.resolve("artifact.pom").toFile
    Files.writeString(pomFile.toPath, "<project/>")

    val app = project("app", workspace.resolve("app").toFile)
    val pomArtifact = new DefaultArtifact(
      "com.example",
      "pom-dep",
      "2.0.0",
      Artifact.SCOPE_COMPILE,
      "pom",
      null,
      new DefaultArtifactHandler("pom"),
    )
    pomArtifact.setFile(pomFile)
    app.setArtifacts(Set[Artifact](pomArtifact).asJava)

    val session = new MavenSession(
      null,
      null.asInstanceOf[RepositorySystemSession],
      new DefaultMavenExecutionRequest(),
      null,
    )
    session.setCurrentProject(app)
    MbtMojoImpl.run(
      new TestMojo(
        List(app),
        output.toFile,
        localRepo.toFile,
        session = session,
      )
    )

    val build = readBuild(output.toFile)
    val depModuleIds = build.dependencyModules.asScala.map(_.id).toSet
    val nsDepIds =
      build.namespaces
        .asScala("com.example:app:1.0.0")
        .dependencyModules
        .asScala
        .toSet

    assert(
      !depModuleIds.contains("com.example:pom-dep:2.0.0"),
      s"non-JAR artifact must not appear in dependencyModules: $depModuleIds",
    )
    assert(
      nsDepIds.isEmpty,
      s"namespace must not reference non-JAR artifact, got: $nsDepIds",
    )
    assert(
      nsDepIds.subsetOf(depModuleIds),
      s"dangling namespace references: ${nsDepIds -- depModuleIds}",
    )
  }

  test("profile-declared module is exported") {
    val workspace = Files.createTempDirectory("mbt-profile-module")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    writePom(
      workspace,
      """|<project>
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>com.example</groupId>
         |  <artifactId>root</artifactId>
         |  <version>1.0.0</version>
         |  <packaging>pom</packaging>
         |  <modules>
         |    <module>core</module>
         |  </modules>
         |  <profiles>
         |    <profile>
         |      <id>full-build</id>
         |      <modules>
         |        <module>it</module>
         |      </modules>
         |    </profile>
         |  </profiles>
         |</project>""".stripMargin,
    )
    writePom(workspace.resolve("core"), modulePom("core"))
    writePom(workspace.resolve("it"), modulePom("it"))

    val root = project("root", workspace.toFile, packaging = "pom")
    val core = project("core", workspace.resolve("core").toFile)

    MbtMojoImpl.run(
      new TestMojo(
        List(root, core),
        output.toFile,
        localRepo.toFile,
        session = mavenSession(root),
        projectBuilder = projectBuilderFromPoms,
      )
    )

    val build = readBuild(output.toFile)
    val namespaces = build.namespaces.asScala.keySet
    assert(namespaces.contains("com.example:it:1.0.0"))
    assert(namespaces.contains("com.example:it:1.0.0:test"))
  }

  test("pom outside declared modules is not exported") {
    val workspace = Files.createTempDirectory("mbt-profile-module-fixture")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    writePom(
      workspace,
      """|<project>
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>com.example</groupId>
         |  <artifactId>root</artifactId>
         |  <version>1.0.0</version>
         |  <packaging>pom</packaging>
         |  <modules>
         |    <module>core</module>
         |  </modules>
         |</project>""".stripMargin,
    )
    writePom(workspace.resolve("core"), modulePom("core"))
    writePom(workspace.resolve("src/it/fixture"), modulePom("fixture"))

    val root = project("root", workspace.toFile, packaging = "pom")
    val core = project("core", workspace.resolve("core").toFile)

    MbtMojoImpl.run(
      new TestMojo(
        List(root, core),
        output.toFile,
        localRepo.toFile,
        session = mavenSession(root),
        projectBuilder = projectBuilderFromPoms,
      )
    )

    val build = readBuild(output.toFile)
    val namespaces = build.namespaces.asScala.keySet
    assert(!namespaces.contains("com.example:fixture:1.0.0"))
    assert(!namespaces.contains("com.example:fixture:1.0.0:test"))
  }

  test("profile-declared module depends on normal reactor module") {
    val workspace = Files.createTempDirectory("mbt-profile-module-dep")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    writePom(
      workspace,
      """|<project>
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>com.example</groupId>
         |  <artifactId>root</artifactId>
         |  <version>1.0.0</version>
         |  <packaging>pom</packaging>
         |  <modules>
         |    <module>core</module>
         |  </modules>
         |  <profiles>
         |    <profile>
         |      <id>full-build</id>
         |      <modules>
         |        <module>it</module>
         |      </modules>
         |    </profile>
         |  </profiles>
         |</project>""".stripMargin,
    )
    writePom(workspace.resolve("core"), modulePom("core"))
    writePom(
      workspace.resolve("it"),
      modulePom(
        "it",
        dependencies = """|  <dependencies>
                          |    <dependency>
                          |      <groupId>com.example</groupId>
                          |      <artifactId>core</artifactId>
                          |      <version>1.0.0</version>
                          |    </dependency>
                          |  </dependencies>""".stripMargin,
      ),
    )

    val root = project("root", workspace.toFile, packaging = "pom")
    val core = project("core", workspace.resolve("core").toFile)

    MbtMojoImpl.run(
      new TestMojo(
        List(root, core),
        output.toFile,
        localRepo.toFile,
        session = mavenSession(root),
        projectBuilder = projectBuilderFromPoms,
      )
    )

    val build = readBuild(output.toFile)
    val dependsOn = build.namespaces
      .asScala("com.example:it:1.0.0")
      .dependsOn
      .asScala
      .toList

    assertEquals(dependsOn, List("com.example:core:1.0.0"))
  }

  test("transitive reactor dependency is not exported as direct dependsOn") {
    val workspace = Files.createTempDirectory("mbt-transitive-reactor-deps")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    val util = project("util", workspace.resolve("util").toFile)
    val core = project("core", workspace.resolve("core").toFile)
    val app = project("app", workspace.resolve("app").toFile)
    app.setDependencies(
      List(
        dependency(
          "core",
          "1.0.0",
          Artifact.SCOPE_COMPILE,
          groupId = "com.example",
        )
      ).asJava
    )
    app.setArtifacts(
      Set[Artifact](
        reactorArtifact("core", Artifact.SCOPE_COMPILE),
        reactorArtifact("util", Artifact.SCOPE_COMPILE),
      ).asJava
    )

    val session = mavenSession(app)
    session.setProjectDependencyGraph(
      projectDependencyGraph(Map(app -> List(core), core -> List(util)))
    )

    MbtMojoImpl.run(
      new TestMojo(
        List(util, core, app),
        output.toFile,
        localRepo.toFile,
        session = session,
      )
    )

    val build = readBuild(output.toFile)
    val dependsOn = build.namespaces
      .asScala("com.example:app:1.0.0")
      .dependsOn
      .asScala
      .toList

    assertEquals(dependsOn, List("com.example:core:1.0.0"))
  }

  test("profile module path can use profile properties") {
    val workspace = Files.createTempDirectory("mbt-profile-module-property")
    val localRepo = Files.createDirectories(workspace.resolve("m2"))
    val output = workspace.resolve("mbt.json")

    writePom(
      workspace,
      """|<project>
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>com.example</groupId>
         |  <artifactId>root</artifactId>
         |  <version>1.0.0</version>
         |  <packaging>pom</packaging>
         |  <modules>
         |    <module>core</module>
         |  </modules>
         |  <profiles>
         |    <profile>
         |      <id>full-build</id>
         |      <properties>
         |        <module.name>it</module.name>
         |      </properties>
         |      <modules>
         |        <module>${module.name}</module>
         |      </modules>
         |    </profile>
         |  </profiles>
         |</project>""".stripMargin,
    )
    writePom(workspace.resolve("core"), modulePom("core"))
    writePom(workspace.resolve("it"), modulePom("it"))

    val root = project("root", workspace.toFile, packaging = "pom")
    val core = project("core", workspace.resolve("core").toFile)

    MbtMojoImpl.run(
      new TestMojo(
        List(root, core),
        output.toFile,
        localRepo.toFile,
        session = mavenSession(root),
        projectBuilder = projectBuilderFromPoms,
      )
    )

    val build = readBuild(output.toFile)
    val namespaces = build.namespaces.asScala.keySet
    assert(namespaces.contains("com.example:it:1.0.0"))
    assert(namespaces.contains("com.example:it:1.0.0:test"))
  }

  private def project(
      artifactId: String,
      basedir: File,
      packaging: String = "jar",
  ): MavenProject = {
    Files.createDirectories(basedir.toPath.resolve("src/main/java"))
    Files.createDirectories(basedir.toPath.resolve("src/test/java"))
    val project = new MavenProject()
    project.setGroupId("com.example")
    project.setArtifactId(artifactId)
    project.setVersion("1.0.0")
    project.setPackaging(packaging)
    project.setFile(basedir.toPath.resolve("pom.xml").toFile)
    val build = new Build()
    build.setDirectory(basedir.toPath.resolve("target").toString)
    project.setBuild(build)
    project.addCompileSourceRoot(
      basedir.toPath.resolve("src/main/java").toString
    )
    project.addTestCompileSourceRoot(
      basedir.toPath.resolve("src/test/java").toString
    )
    project.setArtifacts(Collections.emptySet[Artifact]())
    project
  }

  private def writePom(
      directory: java.nio.file.Path,
      contents: String,
  ): Unit = {
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("pom.xml"), contents)
  }

  private def modulePom(
      artifactId: String,
      dependencies: String = "",
  ): String =
    s"""|<project>
        |  <modelVersion>4.0.0</modelVersion>
        |  <groupId>com.example</groupId>
        |  <artifactId>$artifactId</artifactId>
        |  <version>1.0.0</version>
        |$dependencies
        |</project>""".stripMargin

  private def projectFromPom(pom: File): MavenProject = {
    val model = readModel(pom)
    val basedir = pom.getParentFile
    val project = new MavenProject()
    project.setGroupId(
      Option(model.getGroupId)
        .orElse(Option(model.getParent).map(_.getGroupId))
        .orNull
    )
    project.setArtifactId(model.getArtifactId)
    project.setVersion(
      Option(model.getVersion)
        .orElse(Option(model.getParent).map(_.getVersion))
        .orNull
    )
    project.setPackaging(Option(model.getPackaging).getOrElse("jar"))
    project.setFile(pom)
    val build = new Build()
    build.setDirectory(basedir.toPath.resolve("target").toString)
    project.setBuild(build)
    project.addCompileSourceRoot(
      basedir.toPath.resolve("src/main/java").toString
    )
    project.addTestCompileSourceRoot(
      basedir.toPath.resolve("src/test/java").toString
    )
    project.setDependencies(model.getDependencies)
    project.setArtifacts(Collections.emptySet[Artifact]())
    project
  }

  private def readModel(pom: File): Model = {
    val in = new java.io.FileReader(pom)
    try new MavenXpp3Reader().read(in)
    finally in.close()
  }

  private def projectBuilderFromPoms: ProjectBuilder =
    Proxy
      .newProxyInstance(
        classOf[ProjectBuilder].getClassLoader,
        Array(classOf[ProjectBuilder]),
        (_, method, args) =>
          method.getName match {
            case "build"
                if args != null && args.headOption
                  .exists(_.isInstanceOf[File]) =>
              projectBuildingResult(projectFromPom(args(0).asInstanceOf[File]))
            case "toString" => "test-project-builder"
            case _ => null
          },
      )
      .asInstanceOf[ProjectBuilder]

  private def projectBuildingResult(
      project: MavenProject
  ): ProjectBuildingResult =
    Proxy
      .newProxyInstance(
        classOf[ProjectBuildingResult].getClassLoader,
        Array(classOf[ProjectBuildingResult]),
        (_, method, _) =>
          method.getName match {
            case "getProject" => project
            case "getPomFile" => project.getFile
            case "getProjectId" =>
              s"${project.getGroupId}:${project.getArtifactId}:${project.getVersion}"
            case "getProblems" => Collections.emptyList()
            case "getDependencyResolutionResult" => null
            case "toString" => s"ProjectBuildingResult(${project.getFile})"
            case _ => null
          },
      )
      .asInstanceOf[ProjectBuildingResult]

  private def mavenSession(currentProject: MavenProject): MavenSession = {
    val session = new MavenSession(
      null,
      null.asInstanceOf[RepositorySystemSession],
      new DefaultMavenExecutionRequest(),
      null,
    )
    session.setCurrentProject(currentProject)
    session
  }

  private def dependency(
      artifactId: String,
      version: String,
      scope: String,
      tpe: String = "jar",
      classifier: Option[String] = None,
      groupId: String = "org.libs",
  ): Dependency = {
    val dependency = new Dependency()
    dependency.setGroupId(groupId)
    dependency.setArtifactId(artifactId)
    dependency.setVersion(version)
    dependency.setType(tpe)
    classifier.foreach(dependency.setClassifier)
    dependency.setScope(scope)
    dependency
  }

  private def reactorArtifact(
      artifactId: String,
      scope: String,
  ): Artifact =
    new DefaultArtifact(
      "com.example",
      artifactId,
      "1.0.0",
      scope,
      "jar",
      null,
      new DefaultArtifactHandler("jar"),
    )

  private def projectDependencyGraph(
      upstreamByProject: Map[MavenProject, List[MavenProject]]
  ): ProjectDependencyGraph =
    Proxy
      .newProxyInstance(
        classOf[ProjectDependencyGraph].getClassLoader,
        Array(classOf[ProjectDependencyGraph]),
        (_, method, args) =>
          method.getName match {
            case "getUpstreamProjects"
                if args != null && args.headOption
                  .exists(_.isInstanceOf[MavenProject]) =>
              upstreamByProject
                .getOrElse(args(0).asInstanceOf[MavenProject], Nil)
                .asJava
            case "getSortedProjects" =>
              upstreamByProject.keys.toList.asJava
            case "toString" => "test-project-dependency-graph"
            case _ => null
          },
      )
      .asInstanceOf[ProjectDependencyGraph]

  private def createLocalJar(
      localRepo: File,
      groupId: String,
      artifactId: String,
      version: String,
      classifier: Option[String] = None,
  ): File = {
    val classifierSuffix = classifier.map("-" + _).getOrElse("")
    val jar = new File(
      localRepo,
      s"${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version$classifierSuffix.jar",
    )
    Files.createDirectories(jar.toPath.getParent)
    Files.createFile(jar.toPath)
    jar
  }

  private def createLocalSourcesJar(
      localRepo: File,
      groupId: String,
      artifactId: String,
      version: String,
  ): File = {
    val jar = new File(
      localRepo,
      s"${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version-sources.jar",
    )
    Files.createDirectories(jar.toPath.getParent)
    Files.createFile(jar.toPath)
    jar
  }

  private def readBuild(output: File): MbtBuildJson =
    new Gson().fromJson(Files.readString(output.toPath), classOf[MbtBuildJson])

  private def repoSystemResolving(
      sourcesByCoords: Map[String, File]
  ): RepositorySystem =
    Proxy
      .newProxyInstance(
        classOf[RepositorySystem].getClassLoader,
        Array(classOf[RepositorySystem]),
        (_, method, args) =>
          if (method.getName == "resolveArtifacts") {
            val requests = args(1)
              .asInstanceOf[java.util.Collection[ArtifactRequest]]
              .asScala
            val results = requests.map { req =>
              val art = req.getArtifact
              val coords =
                s"${art.getGroupId}:${art.getArtifactId}:${art.getVersion}"
              val result = new ArtifactResult(req)
              sourcesByCoords.get(coords).foreach { file =>
                result.setArtifact(
                  art.setFile(file)
                )
              }
              result
            }
            new java.util.ArrayList(results.asJavaCollection)
          } else null,
      )
      .asInstanceOf[RepositorySystem]

  private class TestMojo(
      projects: List[MavenProject],
      output: File,
      localRepo: File,
      downloadSources: Boolean = false,
      repoSystem: RepositorySystem = null,
      session: MavenSession = null,
      projectBuilder: ProjectBuilder = null,
  ) extends MbtMojo {
    override def getLog: Log = new SystemStreamLog()
    override def getReactorProjects: java.util.List[MavenProject] =
      projects.asJava
    override def getOutputFile: File = output
    override def isDownloadSources: Boolean = downloadSources
    override def getRepoSystem: RepositorySystem = repoSystem
    override def getProjectBuilder: ProjectBuilder = projectBuilder
    override def getToolchainManager: ToolchainManager = null
    override def getRepositorySession: RepositorySystemSession = null
    override def getLocalRepositoryBasedir: File = localRepo
    override def getSession: MavenSession = session
  }
}
