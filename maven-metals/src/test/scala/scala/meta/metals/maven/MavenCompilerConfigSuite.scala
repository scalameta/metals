package scala.meta.metals.maven

import org.apache.maven.model.Build
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.model.PluginManagement
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

class MavenCompilerConfigSuite extends munit.FunSuite {

  test(
    "returns empty compiler config when no Maven compiler plugins are present"
  ) {
    val config = MavenCompilerConfig.extract(new MavenProject(), isTest = false)

    assertEquals(config, CompilerConfig(Nil, Nil, None))
  }

  test("extracts javac options from Maven properties without compiler plugin") {
    val project = new MavenProject()
    project.getProperties.setProperty("maven.compiler.release", "11")
    project.getProperties.setProperty("maven.compiler.source", "8")
    project.getProperties.setProperty("maven.compiler.target", "8")
    project.getProperties.setProperty("maven.compiler.encoding", "ISO-8859-1")
    project.getProperties.setProperty("air.compiler.enable-preview", "true")
    project.getProperties.setProperty("maven.compiler.parameters", "true")

    val config = MavenCompilerConfig.extract(project, isTest = false)

    assertNoDiff(
      config.javacOptions.mkString("\n"),
      """|--release
         |11
         |-encoding
         |ISO-8859-1
         |--enable-preview
         |-parameters""".stripMargin,
    )
  }

  test("extracts source and target when release is not configured") {
    val project = new MavenProject()
    project.setBuild(new Build())
    project.getProperties.setProperty("maven.compiler.source", "8")
    project.getProperties.setProperty("maven.compiler.target", "11")
    project.getProperties.setProperty("project.build.sourceEncoding", "UTF-8")

    val config = MavenCompilerConfig.extract(project, isTest = false)

    assertNoDiff(
      config.javacOptions.mkString("\n"),
      """|-source
         |8
         |-target
         |11
         |-encoding
         |UTF-8""".stripMargin,
    )
  }

  test(
    "reads compiler plugins from pluginManagement and lets build plugins override"
  ) {
    val project = new MavenProject()
    val build = new Build()
    val pluginManagement = new PluginManagement()
    pluginManagement.addPlugin(
      plugin(
        "org.apache.maven.plugins",
        "maven-compiler-plugin",
        configuration("release" -> "8"),
      )
    )
    pluginManagement.addPlugin(
      plugin(
        "net.alchim31.maven",
        "scala-maven-plugin",
        node(
          "configuration",
          node("scalaCompatVersion", "2.13"),
          node("addScalacArgs", " | -Wunused | "),
        ),
      )
    )
    build.setPluginManagement(pluginManagement)
    build.addPlugin(
      plugin(
        "org.apache.maven.plugins",
        "maven-compiler-plugin",
        configuration("release" -> "17"),
      )
    )
    project.setBuild(build)

    val config = MavenCompilerConfig.extract(project, isTest = false)

    assertNoDiff(config.javacOptions.mkString("\n"), "--release\n17")
    assertNoDiff(config.scalacOptions.mkString("\n"), "-Wunused")
    assertEquals(config.scalaVersion, Some("2.13"))
  }

  test("extracts test-specific javac options using testRelease property") {
    val project = new MavenProject()
    project.setBuild(new Build())
    project.getProperties.setProperty("maven.compiler.release", "17")
    project.getProperties.setProperty("maven.compiler.testRelease", "21")

    val mainConfig = MavenCompilerConfig.extract(project, isTest = false)
    val testConfig = MavenCompilerConfig.extract(project, isTest = true)

    assertNoDiff(mainConfig.javacOptions.mkString("\n"), "--release\n17")
    assertNoDiff(testConfig.javacOptions.mkString("\n"), "--release\n21")
  }

  test("goal-specific javac executions override top-level configuration") {
    val project = new MavenProject()
    project.setBuild(new Build())

    val compiler = plugin(
      "org.apache.maven.plugins",
      "maven-compiler-plugin",
      configuration("release" -> "8"),
    )
    compiler.addExecution(
      execution(
        id = "default-compile",
        goal = "compile",
        configuration("release" -> "11", "compilerArg" -> "-Xmain"),
      )
    )
    compiler.addExecution(
      execution(
        id = "default-testCompile",
        goal = "testCompile",
        configuration("release" -> "17", "compilerArg" -> "-Xtest"),
      )
    )
    project.getBuild.addPlugin(compiler)

    val mainConfig = MavenCompilerConfig.extract(project, isTest = false)
    val testConfig = MavenCompilerConfig.extract(project, isTest = true)

    assertNoDiff(
      mainConfig.javacOptions.mkString("\n"),
      "--release\n11\n-Xmain",
    )
    assertNoDiff(
      testConfig.javacOptions.mkString("\n"),
      "--release\n17\n-Xtest",
    )
  }

  test("goal-specific scalac executions share top-level Scala version") {
    val project = new MavenProject()
    project.setBuild(new Build())

    val scalac = plugin(
      "net.alchim31.maven",
      "scala-maven-plugin",
      node("configuration", node("scalaVersion", "2.13.14")),
    )
    scalac.addExecution(
      execution(
        id = "default-compile",
        goal = "compile",
        node("configuration", node("args", node("arg", "-Xmain"))),
      )
    )
    scalac.addExecution(
      execution(
        id = "default-testCompile",
        goal = "testCompile",
        node("configuration", node("args", node("arg", "-Xtest"))),
      )
    )
    project.getBuild.addPlugin(scalac)

    val mainConfig = MavenCompilerConfig.extract(project, isTest = false)
    val testConfig = MavenCompilerConfig.extract(project, isTest = true)

    assertNoDiff(mainConfig.scalacOptions.mkString("\n"), "-Xmain")
    assertNoDiff(testConfig.scalacOptions.mkString("\n"), "-Xtest")
    assertEquals(mainConfig.scalaVersion, Some("2.13.14"))
    assertEquals(testConfig.scalaVersion, Some("2.13.14"))
  }

  test("extracts annotationProcessorPaths coordinates") {
    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      plugin(
        "org.apache.maven.plugins",
        "maven-compiler-plugin",
        node(
          "configuration",
          node(
            "annotationProcessorPaths",
            node(
              "path",
              node("groupId", "org.projectlombok"),
              node("artifactId", "lombok"),
              node("version", "1.18.34"),
            ),
            node(
              "path",
              node("groupId", "com.google.auto.service"),
              node("artifactId", "auto-service"),
              node("version", "1.1.1"),
            ),
          ),
        ),
      )
    )

    val config = MavenCompilerConfig.extract(project, isTest = false)
    assertNoDiff(
      config.annotationProcessorPaths
        .map { case (g, a, v) => s"$g:$a:$v" }
        .mkString("\n"),
      """|org.projectlombok:lombok:1.18.34
         |com.google.auto.service:auto-service:1.1.1""".stripMargin,
    )
  }

  test("resolves annotationProcessorPaths version from dependencyManagement") {
    val project = new MavenProject()
    project.setBuild(new Build())
    val lombokArtifact = new org.apache.maven.artifact.DefaultArtifact(
      "org.projectlombok",
      "lombok",
      "1.18.34",
      "compile",
      "jar",
      "",
      new org.apache.maven.artifact.handler.DefaultArtifactHandler("jar"),
    )
    project.setManagedVersionMap(
      java.util.Collections
        .singletonMap("org.projectlombok:lombok:jar", lombokArtifact)
    )
    project.getBuild.addPlugin(
      plugin(
        "org.apache.maven.plugins",
        "maven-compiler-plugin",
        node(
          "configuration",
          node(
            "annotationProcessorPaths",
            node(
              "path",
              node("groupId", "org.projectlombok"),
              node("artifactId", "lombok"),
            ),
          ),
        ),
      )
    )

    val config = MavenCompilerConfig.extract(project, isTest = false)
    assertNoDiff(
      config.annotationProcessorPaths
        .map { case (g, a, v) => s"$g:$a:$v" }
        .mkString("\n"),
      "org.projectlombok:lombok:1.18.34",
    )
  }

  private def plugin(
      groupId: String,
      artifactId: String,
      configuration: Xpp3Dom,
  ): Plugin = {
    val plugin = new Plugin()
    plugin.setGroupId(groupId)
    plugin.setArtifactId(artifactId)
    plugin.setConfiguration(configuration)
    plugin
  }

  private def execution(
      id: String,
      goal: String,
      configuration: Xpp3Dom,
  ): PluginExecution = {
    val exec = new PluginExecution()
    exec.setId(id)
    exec.addGoal(goal)
    exec.setConfiguration(configuration)
    exec
  }

  private def configuration(values: (String, String)*): Xpp3Dom =
    node(
      "configuration",
      values.map { case (name, value) => node(name, value) }: _*
    )

  private def node(name: String, value: String): Xpp3Dom = {
    val dom = new Xpp3Dom(name)
    dom.setValue(value)
    dom
  }

  private def node(name: String, children: Xpp3Dom*): Xpp3Dom = {
    val dom = new Xpp3Dom(name)
    children.foreach(dom.addChild)
    dom
  }
}
