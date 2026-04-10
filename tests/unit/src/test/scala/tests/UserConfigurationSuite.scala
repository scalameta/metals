package tests

import java.nio.file.Paths
import java.util.Properties

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.BloopJvmProperties
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.InlayHintsOption
import scala.meta.internal.metals.InlayHintsOptions
import scala.meta.internal.metals.JavaFormatConfig
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.Location
import org.eclipse.lsp4j.InitializeParams

class UserConfigurationSuite extends BaseSuite {
  def check(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty,
  )(
      fn: Either[List[String], UserConfiguration] => Unit
  )(implicit loc: Location): Unit = {
    test(name) {
      val json = UserConfiguration.parse(original)
      val jprops = new Properties()
      // java11 ambiguous .putAll via Properties/Hashtable, use .put
      props.foreach { case (k, v) => jprops.put(k, v) }
      val obtained =
        UserConfiguration.fromJson(json, ClientConfiguration.default, jprops)
      fn(obtained)
    }
  }

  def checkOK(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty,
  )(fn: UserConfiguration => Unit)(implicit loc: Location): Unit = {
    check(name, original, props) {
      case Left(errs) =>
        fail(s"Expected success. Obtained error: $errs")
      case Right(obtained) =>
        fn(obtained)
    }
  }
  def checkError(
      name: String,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    check(name, original) {
      case Right(ok) =>
        fail(s"Expected error. Obtained successful value $ok")
      case Left(errs) =>
        val obtained = errs.mkString("\n")
        assertNoDiff(obtained, expected)
    }
  }

  checkOK(
    "basic",
    """
      |{
      | "java-home": "home",
      | "compile-on-save": "current-project",
      | "sbt-script": "script"
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtScript == Some("script"))
  }

  checkOK(
    "empty-object",
    "{}",
  ) { obtained =>
    assert(obtained.javaHome.isEmpty)
    assert(obtained.sbtScript.isEmpty)
    assert(
      obtained.scalafmtConfigPath ==
        UserConfiguration.default.scalafmtConfigPath
    )
    assert(
      obtained.scalafixConfigPath ==
        UserConfiguration.default.scalafixConfigPath
    )
  }

  checkOK(
    "empty-string",
    "{'java-home':''}",
  ) { obtained => assert(obtained.javaHome.isEmpty) }

  checkOK(
    "sys-props",
    """
      |{
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "home",
      "metals.sbt-script" -> "script",
    ),
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtScript == Some("script"))
  }

  // we support camel case to not break existing clients using `javaHome`.
  checkOK(
    "camel",
    """
      |{
      |  "javaHome": "home"
      |}
    """.stripMargin,
  ) { obtained => assert(obtained.javaHome == Some("home")) }

  checkOK(
    "conflict",
    """
      |{
      |  "java-home": "a"
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "b"
    ),
  ) { obtained => assert(obtained.javaHome == Some("b")) }

  checkOK(
    "empty",
    """
      |{
      |  "java-home": ""
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "b"
    ),
  ) { obtained => assert(obtained.javaHome == Some("b")) }

  checkOK(
    "empty-prop",
    """
      |{
      |  "java-home": "a"
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> ""
    ),
  ) { obtained => assert(obtained.javaHome == Some("a")) }

  checkError(
    "type-mismatch",
    """
      |{
      | "sbt-script": []
      |}
    """.stripMargin,
    """
      |json error: key 'sbt-script' should have value of type string but obtained []
    """.stripMargin,
  )

  checkError(
    "symbol-prefixes",
    """
      |{
      | "symbol-prefixes": {
      |   "a.b": "c"
      | }
      |}
    """.stripMargin,
    "invalid SemanticDB symbol 'a.b': missing descriptor, " +
      "did you mean `a.b/` or `a.b.`? " +
      "(to learn the syntax see https://scalameta.org/docs/semanticdb/specification.html#symbol-1)",
  )

  checkOK(
    "strip-margin false",
    """
      |{
      | "enable-strip-margin-on-type-formatting": false
      |}
    """.stripMargin,
  ) { ok => assert(ok.enableStripMarginOnTypeFormatting == false) }

  checkOK(
    "java format setting",
    """
      |{
      | "javaFormat": {
      |  "eclipseConfigPath": "path",
      |  "eclipseProfile": "profile"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.javaFormatConfig == Some(
        JavaFormatConfig(AbsolutePath("path"), Some("profile"))
      )
    )
  }
  checkOK(
    "java format no setting",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.javaFormatConfig == None)
  }
  checkOK(
    "java format no profile setting",
    """
      |{
      | "javaFormat": {
      |  "eclipseConfigPath": "path"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.javaFormatConfig == Some(
        JavaFormatConfig(AbsolutePath("path"), None)
      )
    )
  }

  test("check-print") {
    val fakePath = AbsolutePath(Paths.get("./.scalafmt.conf"))
    val fakePathString = fakePath.toString().replace("\\", "\\\\")

    val nonDefault = UserConfiguration(
      javaHome = Some("/fake/home"),
      sbtScript = Some("sbt"),
      gradleScript = Some("gradle"),
      mavenScript = Some("mvn"),
      millScript = Some("mill"),
      scalafmtConfigPath = Some(fakePath),
      scalafixConfigPath = Some(fakePath),
      symbolPrefixes = Map("java/util/" -> "hello."),
      worksheetScreenWidth = 140,
      worksheetCancelTimeout = 10,
      bloopSbtAlreadyInstalled = true,
      bloopVersion = Some("1.2.3"),
      bloopJvmProperties =
        BloopJvmProperties.WithProperties(List("a", "b", "c")),
      superMethodLensesEnabled = true,
      inlayHintsOptions = InlayHintsOptions(
        Map(
          InlayHintsOption.HintsInPatternMatch -> true,
          InlayHintsOption.ImplicitArguments -> true,
          InlayHintsOption.InferredType -> true,
          InlayHintsOption.ImplicitConversions -> true,
          InlayHintsOption.TypeParameters -> true,
        )
      ),
      enableStripMarginOnTypeFormatting = false,
      enableIndentOnPaste = true,
      enableSemanticHighlighting = false,
      excludedPackages = Some(List("excluded")),
      fallbackScalaVersion = Some("3.2.1"),
      testUserInterface = TestUserInterfaceKind.TestExplorer,
      javaFormatConfig = Some(JavaFormatConfig(fakePath, Some("profile"))),
      scalafixRulesDependencies = List("rule1", "rule2"),
      customProjectRoot = Some("customs"),
      verboseCompilation = true,
      automaticImportBuild = AutoImportBuildKind.All,
      scalaCliLauncher = Some("scala-cli"),
      defaultBspToBuildTool = true,
    )

    val json = nonDefault.toString()
    assertNoDiff(
      json,
      s"""|{
          |  "enableIndentOnPaste": true,
          |  "millScript": "mill",
          |  "javaFormat": {
          |    "eclipseConfigPath": "$fakePathString",
          |    "eclipseProfile": "profile"
          |  },
          |  "defaultBspToBuildTool": true,
          |  "excludedPackages": [
          |    "excluded"
          |  ],
          |  "bloopJvmProperties": [
          |    "a",
          |    "b",
          |    "c"
          |  ],
          |  "enableStripMarginOnTypeFormatting": false,
          |  "gradleScript": "gradle",
          |  "scalafixConfigPath": "$fakePathString",
          |  "superMethodLensesEnabled": true,
          |  "startMcpServer": false,
          |  "customProjectRoot": "customs",
          |  "scalafixLintEnabled": false,
          |  "bloopSbtAlreadyInstalled": true,
          |  "symbolPrefixes": {
          |    "java/util/": "hello."
          |  },
          |  "inlayHintsOptions": {
          |    "HintsInPatternMatch": "true",
          |    "ImplicitArguments": "true",
          |    "TypeParameters": "true",
          |    "InferredType": "true",
          |    "ImplicitConversions": "true"
          |  },
          |  "scalafixRulesDependencies": [
          |    "rule1",
          |    "rule2"
          |  ],
          |  "testUserInterface": "test explorer",
          |  "bloopVersion": "1.2.3",
          |  "fallbackScalaVersion": "3.2.1",
          |  "autoImportBuilds": "all",
          |  "enableSemanticHighlighting": false,
          |  "scalaCliLauncher": "scala-cli",
          |  "sbtScript": "sbt",
          |  "mavenScript": "mvn",
          |  "verboseCompilation": true,
          |  "worksheetCancelTimeout": 10,
          |  "worksheetScreenWidth": 140,
          |  "enableBestEffort": false,
          |  "scalafmtConfigPath": "$fakePathString",
          |  "javaHome": "/fake/home"
          |}
          |""".stripMargin,
    )
    val roundtripJson = UserConfiguration.parse(json)

    val params = new InitializeParams()
    params.setInitializationOptions(
      Map("testExplorerProvider" -> true).asJava.toJsonObject
    )

    val clientConfig = ClientConfiguration(MetalsServerConfig.default, params)

    val roundtrip = UserConfiguration
      .fromJson(roundtripJson, clientConfig)
      .getOrElse(fail("Failed to parse roundtrip json"))
      // maps have a different order
      .copy(inlayHintsOptions = nonDefault.inlayHintsOptions)
    assertEquals(roundtrip, nonDefault)
  }

  test("list-options") {
    val obtained = UserConfiguration.listOptions
    val bloopVersion = scala.meta.internal.metals.BuildInfo.bloopVersion
    val scala3 = scala.meta.internal.metals.BuildInfo.scala3
    val bloopVersionPadded = f"$bloopVersion%-15s"
    val scala3Padded = f"$scala3%-15s"
    val expected =
      s"""|java-home                                    string                         ""              Java Home directory
          |sbt-script                                   string                         ""              sbt script
          |gradle-script                                string                         ""              Gradle script
          |maven-script                                 string                         ""              Maven script
          |mill-script                                  string                         ""              Mill script
          |scalafmt-config-path                         string                         ""              Scalafmt config path
          |scalafix-config-path                         string                         ""              Scalafix config path
          |scalafix-rules-dependencies                  array                          []              Scalafix rules dependencies
          |scalafix-lint-enabled                        boolean                        false           Enable Scalafix lint diagnostics
          |excluded-packages                            array                          []              Excluded Packages
          |bloop-sbt-already-installed                  boolean                        false           Don't generate Bloop plugin file for sbt
          |bloop-version                                string                         $bloopVersionPadded Version of Bloop
          |bloop-jvm-properties                         array                          ["-Xmx1G"]      Bloop JVM Properties
          |super-method-lenses-enabled                  boolean                        false           Should display lenses with links to super methods
          |inlay-hints.inferred-types.enable            boolean                        false           Should display type annotations for inferred types
          |inlay-hints.named-parameters.enable          boolean                        false           Should display parameter names next to arguments
          |inlay-hints.by-name-parameters.enable        boolean                        false           Should display if a parameter is by-name at usage sites
          |inlay-hints.implicit-arguments.enable        boolean                        false           Should display implicit parameter at usage sites
          |inlay-hints.implicit-conversions.enable      boolean                        false           Should display implicit conversion at usage sites
          |inlay-hints.type-parameters.enable           boolean                        false           Should display type annotations for type parameters
          |inlay-hints.hints-in-pattern-match.enable    boolean                        false           Should display type annotations in pattern matches
          |inlay-hints.hints-x-ray-mode.enable          boolean                        false           Should display type annotations for intermediate types of multi-line expressions
          |inlay-hints.closing-labels.enable            boolean                        false           Should display closing label hints for methods/classes/objects next to their closing braces
          |enable-semantic-highlighting                 boolean                        true            Use semantic tokens highlight
          |enable-indent-on-paste                       boolean                        false           Indent snippets when pasted.
          |fallback-scala-version                       string                         $scala3Padded Default fallback Scala version
          |test-user-interface                          [code lenses,test explorer]    code lenses     Test UI used for tests and test suites
          |java-format.eclipse-config-path              string                         ""              Eclipse Java formatter config path
          |java-format.eclipse-profile                  string                         ""              Eclipse Java formatting profile
          |scala-cli-launcher                           string                         ""              Scala CLI launcher
          |custom-project-root                          string                         ""              Custom project root
          |verbose-compilation                          boolean                        false           Show all compilation debugging information
          |auto-import-builds                           [off,initial,all]              off             Import build when changes detected without prompting
          |target-build-tool                            string                         ""              Preferred build tool when multiple are detected
          |default-bsp-to-build-tool                    boolean                        false           Default to using build tool as your build server.
          |enable-best-effort                           boolean                        false           Use best effort compilation for Scala 3.
          |default-shell                                string                         ""              Full path to the shell executable to be used as the default
          |start-mcp-server                             boolean                        false           Start MCP server
          |mcp-client                                   string                         ""              MCP Client Name""".stripMargin
    assertNoDiff(obtained, expected)
  }

  checkOK(
    "bloop-jvm-properties-uninitialized",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.bloopJvmProperties == BloopJvmProperties.Empty)
  }

  checkOK(
    "bloop-jvm-properties-empty",
    """
      |{
      | "bloop-jvm-properties": []
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.bloopJvmProperties == BloopJvmProperties.WithProperties(Nil)
    )
  }

  checkOK(
    "bloop-jvm-properties-with-values",
    """
      |{
      | "bloop-jvm-properties": ["-Xmx1G", "-Xms512M"]
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.bloopJvmProperties == BloopJvmProperties.WithProperties(
        List("-Xmx1G", "-Xms512M")
      )
    )
  }

  checkOK(
    "target-build-tool-valid",
    """
      |{
      | "target-build-tool": "bazel"
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool == Some("bazel"))
  }

  checkOK(
    "target-build-tool-unset",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool.isEmpty)
  }

  checkOK(
    "target-build-tool-empty-string",
    """
      |{
      | "target-build-tool": ""
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool.isEmpty)
  }

  checkError(
    "target-build-tool-invalid",
    """
      |{
      | "target-build-tool": "invalid-tool"
      |}
    """.stripMargin,
    "Invalid target-build-tool 'invalid-tool'. Valid values are: bazel, gradle, mill, mvn, sbt, scala-cli",
  )

  checkOK(
    "target-build-tool-all-valid-values",
    """
      |{
      | "target-build-tool": "sbt"
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool == Some("sbt"))
  }
}
