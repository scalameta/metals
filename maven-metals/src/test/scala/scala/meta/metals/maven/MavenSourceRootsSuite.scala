package scala.meta.metals.maven

import java.nio.file.Files
import java.nio.file.Path

import org.apache.maven.model.Build
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.model.PluginManagement
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
class MavenSourceRootsSuite extends munit.FunSuite {

  import MavenPluginSupport._

  // ── helpers ──────────────────────────────────────────────────────────────

  private def project(buildDir: String = "/project/target"): MavenProject = {
    val p = new MavenProject()
    val build = new Build()
    build.setDirectory(buildDir)
    build.setSourceDirectory(s"$buildDir/../src/main/java")
    build.setTestSourceDirectory(s"$buildDir/../src/test/java")
    p.setBuild(build)
    p
  }

  private def projectAt(workspace: Path): MavenProject = {
    val p = new MavenProject()
    p.setFile(workspace.resolve("pom.xml").toFile)
    val build = new Build()
    build.setDirectory(workspace.resolve("target").toString)
    p.setBuild(build)
    p
  }

  private def pluginWith(key: String, cfg: Xpp3Dom): Plugin = {
    val Array(g, a) = key.split(":")
    val plugin = new Plugin()
    plugin.setGroupId(g)
    plugin.setArtifactId(a)
    plugin.setConfiguration(cfg)
    plugin
  }

  private def executionWith(goal: String, cfg: Xpp3Dom): PluginExecution = {
    val e = new PluginExecution()
    e.addGoal(goal)
    e.setConfiguration(cfg)
    e
  }

  private def projectWithManagedPlugin(
      key: String,
      cfg: Xpp3Dom,
      buildDir: String = "/project/target",
  ): MavenProject = {
    val p = project(buildDir)
    val pm = new PluginManagement()
    pm.addPlugin(pluginWith(key, cfg))
    p.getBuild.setPluginManagement(pm)
    p
  }

  private def node(name: String, value: String): Xpp3Dom = {
    val d = new Xpp3Dom(name)
    d.setValue(value)
    d
  }

  private def node(name: String, children: Xpp3Dom*): Xpp3Dom = {
    val d = new Xpp3Dom(name)
    children.foreach(d.addChild)
    d
  }

  private def sourceRoots(p: MavenProject, isTest: Boolean): List[String] = {
    val rawRoots =
      if (isTest) p.getTestCompileSourceRoots
      else p.getCompileSourceRoots
    MavenSourceRoots.existingSources(rawRoots, p, isTest)
  }

  test(
    "existing declared roots are filtered and Scala fallback is added once"
  ) {
    val workspace = Files.createTempDirectory("maven-source-roots-existing")
    val mainJava = Files.createDirectories(workspace.resolve("src/main/java"))
    val mainScala = Files.createDirectories(workspace.resolve("src/main/scala"))
    val missing = workspace.resolve("src/main/missing")
    val p = projectAt(workspace)
    p.addCompileSourceRoot(mainJava.toString)
    p.addCompileSourceRoot(missing.toString)

    val roots = sourceRoots(p, isTest = false)

    assertNoDiff(
      roots.mkString("\n"),
      List(mainJava.toFile.getAbsolutePath, mainScala.toFile.getAbsolutePath)
        .mkString("\n"),
    )
  }

  test(
    "generated source scan detects parent, nested layout, Kotlin and Groovy"
  ) {
    val workspace = Files.createTempDirectory("maven-generated-source-roots")
    val generated = workspace.resolve("target/generated-sources")
    Files.createDirectories(generated)
    Files.writeString(generated.resolve("Top.kt"), "class Top")
    val nestedJava =
      Files.createDirectories(generated.resolve("openapi/src/main/java"))
    Files.writeString(nestedJava.resolve("OpenApi.java"), "class OpenApi {}")
    val directGroovy = Files.createDirectories(generated.resolve("gmaven"))
    Files.writeString(directGroovy.resolve("Script.groovy"), "class Script {}")
    val p = projectAt(workspace)

    val roots = sourceRoots(p, isTest = false)

    assert(roots.contains(generated.toFile.getAbsolutePath))
    assert(roots.contains(nestedJava.toFile.getAbsolutePath))
    assert(roots.contains(directGroovy.toFile.getAbsolutePath))
  }

  test("modello default root is added for main sources only") {
    val p = project()
    val plugin = pluginWith(ModelloMavenPlugin, node("configuration"))
    plugin.addExecution(executionWith("java", node("configuration")))
    p.getBuild.addPlugin(plugin)

    val mainRoots = sourceRoots(p, isTest = false)
    val testRoots = sourceRoots(p, isTest = true)

    assert(mainRoots.contains("/project/target/generated-sources/modello"))
    assert(!testRoots.contains("/project/target/generated-sources/modello"))
  }

  test("antlr default root is added for main sources only") {
    val p = project()
    val plugin = pluginWith(Antlr4MavenPlugin, node("configuration"))
    plugin.addExecution(executionWith("antlr4", node("configuration")))
    p.getBuild.addPlugin(plugin)

    val mainRoots = sourceRoots(p, isTest = false)
    val testRoots = sourceRoots(p, isTest = true)

    assert(mainRoots.contains("/project/target/generated-sources/antlr4"))
    assert(!testRoots.contains("/project/target/generated-sources/antlr4"))
  }

  // ── annotation processing ────────────────────────────────────────────────

  test("annotation processing: bare compiler plugin does NOT add AP root") {
    val p = project()
    p.getBuild.addPlugin(pluginWith(JavaCompilerPlugin, node("configuration")))
    val roots = sourceRoots(p, isTest = false)
    assert(!roots.contains("/project/target/generated-sources/annotations"))
  }

  test(
    "annotation processing: annotationProcessors element triggers default main root"
  ) {
    val p = project()
    p.getBuild.addPlugin(
      pluginWith(
        JavaCompilerPlugin,
        node("configuration", node("annotationProcessors")),
      )
    )
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/project/target/generated-sources/annotations"))
  }

  test(
    "annotation processing: annotationProcessorPaths element triggers default main root"
  ) {
    val p = project()
    p.getBuild.addPlugin(
      pluginWith(
        JavaCompilerPlugin,
        node("configuration", node("annotationProcessorPaths")),
      )
    )
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/project/target/generated-sources/annotations"))
  }

  test(
    "annotation processing: default test root is generated-test-sources/test-annotations"
  ) {
    val p = project()
    p.getBuild.addPlugin(
      pluginWith(
        JavaCompilerPlugin,
        node("configuration", node("annotationProcessors")),
      )
    )
    val roots = sourceRoots(p, isTest = true)
    assert(
      roots.contains("/project/target/generated-test-sources/test-annotations")
    )
    assert(
      !roots.contains("/project/target/generated-test-sources/annotations")
    )
  }

  test(
    "annotation processing: proc=none suppresses root even with annotationProcessors"
  ) {
    val p = project()
    p.getBuild.addPlugin(
      pluginWith(
        JavaCompilerPlugin,
        node(
          "configuration",
          node("proc", "none"),
          node("annotationProcessors"),
        ),
      )
    )
    val roots = sourceRoots(p, isTest = false)
    assert(!roots.contains("/project/target/generated-sources/annotations"))
  }

  test(
    "annotation processing: AP config in testCompile execution detected for tests"
  ) {
    val p = project()
    val plugin = pluginWith(JavaCompilerPlugin, node("configuration"))
    plugin.addExecution(
      executionWith(
        "testCompile",
        node("configuration", node("annotationProcessorPaths")),
      )
    )
    p.getBuild.addPlugin(plugin)
    val roots = sourceRoots(p, isTest = true)
    assert(
      roots.contains("/project/target/generated-test-sources/test-annotations")
    )
  }

  // ── protobuf ─────────────────────────────────────────────────────────────

  test("protobuf: no executions → both java and grpc-java roots for main") {
    val p = project()
    p.getBuild.addPlugin(pluginWith(ProtobufMavenPlugin, node("configuration")))
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/project/target/generated-sources/protobuf/java"))
    assert(
      roots.contains("/project/target/generated-sources/protobuf/grpc-java")
    )
  }

  test(
    "protobuf: no executions → both java and grpc-java roots for tests (default lifecycle binding)"
  ) {
    val p = project()
    p.getBuild.addPlugin(pluginWith(ProtobufMavenPlugin, node("configuration")))
    val roots = sourceRoots(p, isTest = true)
    assert(
      roots.contains("/project/target/generated-test-sources/protobuf/java")
    )
    assert(
      roots.contains(
        "/project/target/generated-test-sources/protobuf/grpc-java"
      )
    )
  }

  test(
    "protobuf: plugin-level outputDirectory visible even when execution has no output"
  ) {
    val p = project()
    val plugin =
      pluginWith(
        ProtobufMavenPlugin,
        node("configuration", node("outputDirectory", "/plugin/proto")),
      )
    plugin.addExecution(executionWith("compile", node("configuration")))
    p.getBuild.addPlugin(plugin)
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/plugin/proto"))
  }

  test("protobuf: explicit compile-only executions suppress test roots") {
    val p = project()
    val plugin = pluginWith(ProtobufMavenPlugin, node("configuration"))
    plugin.addExecution(
      executionWith(
        "compile",
        node("configuration", node("outputDirectory", "/exec/proto")),
      )
    )
    p.getBuild.addPlugin(plugin)
    val roots = sourceRoots(p, isTest = true)
    assert(
      !roots.contains("/project/target/generated-test-sources/protobuf/java")
    )
    assert(
      !roots.contains(
        "/project/target/generated-test-sources/protobuf/grpc-java"
      )
    )
    assert(!roots.contains("/exec/proto"))
  }

  test(
    "protobuf: two executions with distinct outputDirectories yield two roots"
  ) {
    val p = project()
    val plugin = pluginWith(ProtobufMavenPlugin, node("configuration"))
    plugin.addExecution(
      executionWith(
        "compile",
        node("configuration", node("outputDirectory", "/out/a")),
      )
    )
    plugin.addExecution(
      executionWith(
        "compile-custom",
        node("configuration", node("outputDirectory", "/out/b")),
      )
    )
    p.getBuild.addPlugin(plugin)
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/out/a"))
    assert(roots.contains("/out/b"))
  }

  // ── build-helper ─────────────────────────────────────────────────────────

  test(
    "build-helper: add-source and add-test-source in same plugin are segregated"
  ) {
    val p = project()
    val plugin = pluginWith(BuildHelperMavenPlugin, node("configuration"))
    plugin.addExecution(
      executionWith(
        "add-source",
        node(
          "configuration",
          node("sources", node("source", "/project/src/gen/main")),
        ),
      )
    )
    plugin.addExecution(
      executionWith(
        "add-test-source",
        node(
          "configuration",
          node("sources", node("source", "/project/src/gen/test")),
        ),
      )
    )
    p.getBuild.addPlugin(plugin)

    val mainRoots = sourceRoots(p, isTest = false)
    val testRoots = sourceRoots(p, isTest = true)

    assert(mainRoots.contains("/project/src/gen/main"))
    assert(!mainRoots.contains("/project/src/gen/test"))
    assert(testRoots.contains("/project/src/gen/test"))
    assert(!testRoots.contains("/project/src/gen/main"))
  }

  // ── pluginManagement inheritance (multi-module pattern) ──────────────────

  test(
    "generator declared only in pluginManagement is still detected (protobuf)"
  ) {
    val p = projectWithManagedPlugin(ProtobufMavenPlugin, node("configuration"))
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/project/target/generated-sources/protobuf/java"))
    assert(
      roots.contains("/project/target/generated-sources/protobuf/grpc-java")
    )
  }

  test(
    "generator in pluginManagement with custom outputDirectory is respected"
  ) {
    val p = projectWithManagedPlugin(
      ProtobufMavenPlugin,
      node("configuration", node("outputDirectory", "/custom/proto")),
    )
    val roots = sourceRoots(p, isTest = false)
    assert(roots.contains("/custom/proto"))
  }

  test("each module uses its own build directory for generator roots") {
    val moduleA = projectWithManagedPlugin(
      ProtobufMavenPlugin,
      node("configuration"),
      buildDir = "/parent/module-a/target",
    )
    val moduleB = projectWithManagedPlugin(
      ProtobufMavenPlugin,
      node("configuration"),
      buildDir = "/parent/module-b/target",
    )
    val rootsA = sourceRoots(moduleA, isTest = false)
    val rootsB = sourceRoots(moduleB, isTest = false)
    assert(
      rootsA.contains("/parent/module-a/target/generated-sources/protobuf/java")
    )
    assert(
      rootsB.contains("/parent/module-b/target/generated-sources/protobuf/java")
    )
    assert(
      !rootsA.contains(
        "/parent/module-b/target/generated-sources/protobuf/java"
      )
    )
  }

}
