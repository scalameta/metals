package scala.meta.metals.maven

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.{util => ju}

import scala.jdk.CollectionConverters._

import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Build
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.logging.SystemStreamLog
import org.apache.maven.project.MavenProject
import org.apache.maven.toolchain.Toolchain
import org.apache.maven.toolchain.ToolchainManager
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
class JavaHomeResolverSuite extends munit.FunSuite {

  test("selects a JDK satisfying version and enforcer vendor requirements") {
    val workspace = Files.createTempDirectory("jdk-selection")
    val azul21 = fakeJdk(workspace, "azul-21", "21.0.8", "Azul Systems, Inc.")
    val temurin25 =
      fakeJdk(workspace, "temurin-25", "25.0.2", "Eclipse Adoptium")
    val oracle25 =
      fakeJdk(workspace, "oracle-25", "25.0.1", "Oracle Corporation")

    val selected = JavaHomeResolver.selectJavaHome(
      project = projectWithJavaRequirement(
        version = "25.0.1",
        vendors = List("Eclipse Adoptium", "Oracle Corporation"),
      ),
      candidates = List(azul21, temurin25, oracle25).map(_.toString),
    )

    assertEquals(selected, Some(temurin25.toString))
  }

  test("returns None when no candidate JDK satisfies project requirements") {
    val workspace = Files.createTempDirectory("jdk-missing")
    val azul21 = fakeJdk(workspace, "azul-21", "21.0.8", "Azul Systems, Inc.")

    val selected = JavaHomeResolver.selectJavaHome(
      project = projectWithJavaRequirement(
        version = "25.0.1",
        vendors = List("Eclipse Adoptium"),
      ),
      candidates = List(azul21.toString),
    )

    assertEquals(selected, None)
  }

  test(
    "resolveFromForkExecutable interpolates property references in executable path"
  ) {
    val workspace = Files.createTempDirectory("jdk-fork-interpolate")
    val jdk21 = Files.createDirectories(workspace.resolve("jdk-21"))
    val bin = Files.createDirectories(jdk21.resolve("bin"))
    Files.createFile(bin.resolve("javac"))

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getProperties.setProperty("my.jdk.home", jdk21.toString)
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "true"),
          node("executable", "${my.jdk.home}/bin/javac"),
        )
      )
    )

    val home =
      JavaHomeResolver.fromForkExecutable(project, isTest = false)
    assertEquals(home, Some(canonicalHome(jdk21)))
  }

  test("resolveFromForkExecutable canonicalizes symlinked executable path") {
    val workspace = Files.createTempDirectory("jdk-fork-symlink")
    val jdk21 = Files.createDirectories(workspace.resolve("jdk-21"))
    val bin = Files.createDirectories(jdk21.resolve("bin"))
    val javac = Files.createFile(bin.resolve("javac"))
    val shimBin = Files.createDirectories(workspace.resolve("shims"))
    val javacShim = createSymbolicLink(shimBin.resolve("javac"), javac)

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "true"),
          node("executable", javacShim.toString),
        )
      )
    )

    val home =
      JavaHomeResolver.fromForkExecutable(project, isTest = false)
    assertEquals(home, Some(canonicalHome(jdk21)))
  }

  test("resolveFromForkExecutable resolves bare command name from PATH") {
    val workspace = Files.createTempDirectory("jdk-fork-bare")
    val jdk21 = Files.createDirectories(workspace.resolve("jdk-21"))
    val bin = Files.createDirectories(jdk21.resolve("bin"))
    val javac21 = bin.resolve("javac21")
    Files.createFile(javac21)
    javac21.toFile.setExecutable(true)

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "true"),
          node("executable", "javac21"),
        )
      )
    )

    val home = JavaHomeResolver.fromForkExecutable(
      project,
      isTest = false,
      pathDirs = Seq(bin.toString),
    )
    assertEquals(home, Some(canonicalHome(jdk21)))
  }

  test("resolveFromForkExecutable returns None when bare command not on PATH") {
    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "true"),
          node("executable", "javac-nonexistent"),
        )
      )
    )

    assertEquals(
      JavaHomeResolver.fromForkExecutable(
        project,
        isTest = false,
        pathDirs = Seq.empty,
      ),
      None,
    )
  }

  test("resolveFromForkExecutable returns None when fork is false") {
    val workspace = Files.createTempDirectory("jdk-nofork")
    val bin = Files.createDirectories(workspace.resolve("jdk-21/bin"))
    Files.createFile(bin.resolve("javac"))

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "false"),
          node("executable", bin.resolve("javac").toString),
        )
      )
    )

    assertEquals(
      JavaHomeResolver.fromForkExecutable(project, isTest = false),
      None,
    )
  }

  test("matches common JDK vendor aliases") {
    val workspace = Files.createTempDirectory("jdk-vendor-alias")
    val temurin = fakeJdk(workspace, "temurin-21", "21.0.8", "Eclipse Adoptium")

    val selected = JavaHomeResolver.selectJavaHome(
      project = projectWithJavaRequirement(
        version = "21",
        vendors = List("temurin"),
      ),
      candidates = List(temurin.toString),
    )

    assertEquals(selected, Some(temurin.toString))
  }

  test(
    "selectJavaHome uses the highest version requirement from project properties"
  ) {
    val workspace = Files.createTempDirectory("jdk-max-version")
    val jdk17 = fakeJdk(workspace, "jdk-17", "17.0.12", "Oracle Corporation")
    val jdk21 = fakeJdk(workspace, "jdk-21", "21.0.8", "Oracle Corporation")
    val project = new MavenProject()
    project.getProperties.setProperty("java.version", "17")
    project.getProperties.setProperty("jdk.version", "21")
    project.setBuild(new Build())

    val selected = JavaHomeResolver.selectJavaHome(
      project,
      List(jdk17.toString, jdk21.toString),
    )

    assertEquals(selected, Some(jdk21.toString))
  }

  test("selectJavaHome reads JDK release metadata from parent directory") {
    val workspace = Files.createTempDirectory("jdk-parent-release")
    val contents = Files.createDirectories(workspace.resolve("jdk/Contents"))
    val home = Files.createDirectories(contents.resolve("Home"))
    Files.writeString(
      contents.resolve("release"),
      """|JAVA_VERSION="21.0.8"
         |IMPLEMENTOR="GraalVM Community"
         |""".stripMargin,
    )

    val selected = JavaHomeResolver.selectJavaHome(
      projectWithJavaRequirement(version = "21", vendors = List("graalvm")),
      List(home.toString),
    )

    assertEquals(selected, Some(home.toString))
  }

  test("javacOptionsRequirements extracts version from javac options") {
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(List("--release", "21")),
      Some(Map("version" -> "21")),
    )
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(
        List("-source", "17", "-target", "17")
      ),
      Some(Map("version" -> "17")),
    )
    assertEquals(JavaHomeResolver.javacOptionsRequirements(Nil), None)
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(List("-encoding", "UTF-8")),
      None,
    )
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(List("--release=21")),
      Some(Map("version" -> "21")),
    )
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(List("-source=17")),
      Some(Map("version" -> "17")),
    )
    // space form takes priority over = form when both present
    assertEquals(
      JavaHomeResolver.javacOptionsRequirements(
        List("--release", "21", "--release=17")
      ),
      Some(Map("version" -> "21")),
    )
  }

  test("resolve canonicalizes active maven session toolchain java path") {
    val workspace = Files.createTempDirectory("jdk-session-symlink")
    val jdk21 = Files.createDirectories(workspace.resolve("jdk-21"))
    val bin = Files.createDirectories(jdk21.resolve("bin"))
    val java = Files.createFile(bin.resolve("java"))
    val shimBin = Files.createDirectories(workspace.resolve("shims"))
    val javaShim = createSymbolicLink(shimBin.resolve("java"), java)

    val result = JavaHomeResolver.resolve(
      Nil,
      new MavenProject(),
      false,
      mojoWithActiveJavaTool(javaShim),
    )
    assertEquals(result, Some(canonicalHome(jdk21)))
  }

  test(
    "resolve maps full property version to major for toolchain lookup"
  ) {
    // regression: air.java.version=25.0.1 must match a toolchain with version=25
    val workspace = Files.createTempDirectory("jdk-property-version")
    val jdk25 = fakeJdk(workspace, "temurin-25", "25.0.1", "Eclipse Adoptium")
    val project = new MavenProject()
    project.getProperties.setProperty("air.java.version", "25.0.1")
    project.setBuild(new Build())
    val result = JavaHomeResolver.resolve(
      Nil,
      project,
      false,
      mojoWithToolchains(Map(Map("version" -> "25") -> jdk25.toString)),
    )
    assertEquals(result, Some(jdk25.toString))
  }

  test("resolve prioritizes fork/executable over every toolchain source") {
    val workspace = Files.createTempDirectory("jdk-priority")
    val forkJdk = Files.createDirectories(workspace.resolve("fork-jdk"))
    val bin = Files.createDirectories(forkJdk.resolve("bin"))
    Files.createFile(bin.resolve("javac"))

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(
      compilerPlugin(
        node(
          "configuration",
          node("fork", "true"),
          node("executable", bin.resolve("javac").toString),
          node("jdkToolchain", node("version", "21")),
        )
      )
    )

    val result = JavaHomeResolver.resolve(
      Nil,
      project,
      false,
      mojoWithActiveToolchains(
        activeJdkHome = "/fake/session-jdk",
        byReqs = Map(Map("version" -> "21") -> "/fake/compiler-toolchain-jdk"),
      ),
    )
    assertEquals(result, Some(canonicalHome(forkJdk)))
  }

  test(
    "resolve matches toolchain by version even when enforcer vendor differs from toolchain vendor"
  ) {
    // Eclipse Adoptium (enforcer) and temurin (toolchains.xml) are the same vendor —
    // vendor is stripped before querying ToolchainManager; selectJavaHome handles aliases.
    val workspace = Files.createTempDirectory("jdk-vendor-mismatch")
    val jdk25 = fakeJdk(workspace, "temurin-25", "25.0.1", "Eclipse Adoptium")

    val project = new MavenProject()
    project.getProperties.setProperty("air.java.version", "25.0.1")
    project.setBuild(new Build())
    project.getBuild.addPlugin(enforcerPlugin(List("Eclipse Adoptium")))

    val result = JavaHomeResolver.resolve(
      Nil,
      project,
      false,
      // toolchain keyed with "temurin" — would fail with exact vendor match
      mojoWithToolchains(Map(Map("version" -> "25") -> jdk25.toString)),
    )
    assertEquals(result, Some(jdk25.toString))
  }

  test(
    "resolve uses toolchains plugin config over properties and javac options"
  ) {
    val tcJdkHome = "/fake/jdk17-from-toolchains-plugin"
    val propJdkHome = "/fake/jdk25-from-property"
    val javacOptionsJdkHome = "/fake/jdk21-from-javac-options"

    val tcPlugin = new Plugin()
    tcPlugin.setGroupId("org.apache.maven.plugins")
    tcPlugin.setArtifactId("maven-toolchains-plugin")
    tcPlugin.setConfiguration(
      node(
        "configuration",
        node("toolchains", node("jdk", node("version", "17"))),
      )
    )
    val project = new MavenProject()
    project.getProperties.setProperty("air.java.version", "25.0.1")
    project.setBuild(new Build())
    project.getBuild.addPlugin(tcPlugin)

    val result = JavaHomeResolver.resolve(
      List("--release", "21"),
      project,
      false,
      mojoWithToolchains(
        Map(
          Map("version" -> "17") -> tcJdkHome,
          Map("version" -> "21") -> javacOptionsJdkHome,
          Map("version" -> "25") -> propJdkHome,
        )
      ),
    )
    assertEquals(result, Some(tcJdkHome))
  }

  test("resolve uses neutral toolchains plugin execution for main and test") {
    val jdkHome = "/fake/jdk19-neutral"
    val tcPlugin = new Plugin()
    tcPlugin.setGroupId("org.apache.maven.plugins")
    tcPlugin.setArtifactId("maven-toolchains-plugin")
    tcPlugin.addExecution(
      execution(
        id = "select-jdk",
        goal = "toolchain",
        node(
          "configuration",
          node("toolchains", node("jdk", node("version", "19"))),
        ),
      )
    )

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(tcPlugin)
    val mojo = mojoWithToolchains(Map(Map("version" -> "19") -> jdkHome))

    assertEquals(
      JavaHomeResolver.resolve(Nil, project, false, mojo),
      Some(jdkHome),
    )
    assertEquals(
      JavaHomeResolver.resolve(Nil, project, true, mojo),
      Some(jdkHome),
    )
  }

  test("resolve chooses maven-toolchains-plugin execution for main or test") {
    val mainJdkHome = "/fake/jdk17-main"
    val testJdkHome = "/fake/jdk21-test"

    val tcPlugin = new Plugin()
    tcPlugin.setGroupId("org.apache.maven.plugins")
    tcPlugin.setArtifactId("maven-toolchains-plugin")
    tcPlugin.addExecution(
      execution(
        id = "default-compile",
        goal = "toolchain",
        phase = "compile",
        node(
          "configuration",
          node("toolchains", node("jdk", node("version", "17"))),
        ),
      )
    )
    tcPlugin.addExecution(
      execution(
        id = "default-testCompile",
        goal = "toolchain",
        phase = "test-compile",
        node(
          "configuration",
          node("toolchains", node("jdk", node("version", "21"))),
        ),
      )
    )

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(tcPlugin)
    val mojo = mojoWithToolchains(
      Map(
        Map("version" -> "17") -> mainJdkHome,
        Map("version" -> "21") -> testJdkHome,
      )
    )

    assertEquals(
      JavaHomeResolver.resolve(Nil, project, false, mojo),
      Some(mainJdkHome),
    )
    assertEquals(
      JavaHomeResolver.resolve(Nil, project, true, mojo),
      Some(testJdkHome),
    )
  }

  test("resolve merges toolchains plugin execution and plugin config") {
    val jdkHome = "/fake/jdk17-temurin"

    val tcPlugin = new Plugin()
    tcPlugin.setGroupId("org.apache.maven.plugins")
    tcPlugin.setArtifactId("maven-toolchains-plugin")
    tcPlugin.setConfiguration(
      node(
        "configuration",
        node("toolchains", node("jdk", node("vendor", "temurin"))),
      )
    )
    tcPlugin.addExecution(
      execution(
        id = "default-compile",
        goal = "toolchain",
        phase = "compile",
        node(
          "configuration",
          node("toolchains", node("jdk", node("version", "17"))),
        ),
      )
    )

    val project = new MavenProject()
    project.setBuild(new Build())
    project.getBuild.addPlugin(tcPlugin)

    val result = JavaHomeResolver.resolve(
      Nil,
      project,
      false,
      mojoWithToolchains(
        Map(Map("version" -> "17", "vendor" -> "temurin") -> jdkHome)
      ),
    )
    assertEquals(result, Some(jdkHome))
  }

  private def mojoWithActiveJavaTool(javaPath: Path): MbtMojo =
    new MbtMojo {
      override def getLog: Log = new SystemStreamLog()
      override def getReactorProjects = ju.Collections.emptyList()
      override def getOutputFile: File = null
      override def isDownloadSources = false
      override def getRepoSystem: RepositorySystem = null
      override def getRepositorySession: RepositorySystemSession = null
      override def getLocalRepositoryBasedir: File = null
      override def getSession: MavenSession = null
      override def getToolchainManager: ToolchainManager =
        new ToolchainManager {
          override def getToolchainFromBuildContext(
              t: String,
              s: MavenSession,
          ): Toolchain = mkToolchainFromJava(javaPath.toString)
          override def getToolchains(
              s: MavenSession,
              t: String,
              reqs: ju.Map[String, String],
          ): ju.List[Toolchain] = ju.Collections.emptyList()
        }
    }

  private def mojoWithToolchains(
      byReqs: Map[Map[String, String], String]
  ): MbtMojo =
    new MbtMojo {
      override def getLog: Log = new SystemStreamLog()
      override def getReactorProjects = ju.Collections.emptyList()
      override def getOutputFile: File = null
      override def isDownloadSources = false
      override def getRepoSystem: RepositorySystem = null
      override def getRepositorySession: RepositorySystemSession = null
      override def getLocalRepositoryBasedir: File = null
      override def getSession: MavenSession = null
      override def getToolchainManager: ToolchainManager =
        new ToolchainManager {
          override def getToolchainFromBuildContext(
              t: String,
              s: MavenSession,
          ): Toolchain = null
          override def getToolchains(
              s: MavenSession,
              t: String,
              reqs: ju.Map[String, String],
          ): ju.List[Toolchain] =
            byReqs.get(reqs.asScala.toMap) match {
              case Some(home) => ju.List.of(mkToolchain(home))
              case None => ju.Collections.emptyList()
            }
        }
    }

  private def mojoWithActiveToolchains(
      activeJdkHome: String,
      byReqs: Map[Map[String, String], String],
  ): MbtMojo =
    new MbtMojo {
      override def getLog: Log = new SystemStreamLog()
      override def getReactorProjects = ju.Collections.emptyList()
      override def getOutputFile: File = null
      override def isDownloadSources = false
      override def getRepoSystem: RepositorySystem = null
      override def getRepositorySession: RepositorySystemSession = null
      override def getLocalRepositoryBasedir: File = null
      override def getSession: MavenSession = null
      override def getToolchainManager: ToolchainManager =
        new ToolchainManager {
          override def getToolchainFromBuildContext(
              t: String,
              s: MavenSession,
          ): Toolchain = mkToolchain(activeJdkHome)
          override def getToolchains(
              s: MavenSession,
              t: String,
              reqs: ju.Map[String, String],
          ): ju.List[Toolchain] =
            byReqs.get(reqs.asScala.toMap) match {
              case Some(home) => ju.List.of(mkToolchain(home))
              case None => ju.Collections.emptyList()
            }
        }
    }

  private def mkToolchain(jdkHome: String): Toolchain = new Toolchain {
    override def getType = "jdk"
    override def findTool(name: String): String =
      if (name == "java") s"$jdkHome/bin/java" else null
  }

  private def mkToolchainFromJava(javaPath: String): Toolchain = new Toolchain {
    override def getType = "jdk"
    override def findTool(name: String): String =
      if (name == "java") javaPath else null
  }

  private def fakeJdk(
      workspace: Path,
      name: String,
      version: String,
      implementor: String,
  ): Path = {
    val home = workspace.resolve(name)
    Files.createDirectories(home)
    Files.writeString(
      home.resolve("release"),
      s"""|JAVA_VERSION="$version"
          |IMPLEMENTOR="$implementor"
          |""".stripMargin,
    )
    home
  }

  private def projectWithJavaRequirement(
      version: String,
      vendors: List[String],
  ): MavenProject = {
    val project = new MavenProject()
    project.getProperties.setProperty("air.java.version", version)
    val build = new Build()
    build.addPlugin(enforcerPlugin(vendors))
    project.setBuild(build)
    project
  }

  private def enforcerPlugin(vendors: List[String]): Plugin = {
    val plugin = new Plugin()
    plugin.setGroupId("org.apache.maven.plugins")
    plugin.setArtifactId("maven-enforcer-plugin")
    plugin.setConfiguration(
      node(
        "configuration",
        node(
          "rules",
          node(
            "requireJavaVendor",
            node("includes", vendors.map(vendor => node("include", vendor)): _*),
          ),
        ),
      )
    )
    plugin
  }

  private def compilerPlugin(configuration: Xpp3Dom): Plugin = {
    val plugin = new Plugin()
    plugin.setGroupId("org.apache.maven.plugins")
    plugin.setArtifactId("maven-compiler-plugin")
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

  private def execution(
      id: String,
      goal: String,
      phase: String,
      configuration: Xpp3Dom,
  ): PluginExecution = {
    val exec = execution(id, goal, configuration)
    exec.setPhase(phase)
    exec
  }

  private def createSymbolicLink(link: Path, target: Path): Path =
    try Files.createSymbolicLink(link, target)
    catch {
      case e: UnsupportedOperationException =>
        assume(false, "symbolic links are not supported on this filesystem")
        throw e
      case e: SecurityException =>
        assume(false, "symbolic links are not allowed on this filesystem")
        throw e
    }

  private def canonicalHome(path: Path): String =
    JavaHomeResolver.canonicalizePath(path.toString).toString

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
