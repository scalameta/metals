package tests

import java.nio.file.Paths
import java.util.Optional
import java.util.Properties

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.infra.NoopFeatureFlagProvider
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.InlayHintsOptions
import scala.meta.internal.metals.JavaFormatterConfig
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.config.AutoImportBuildKind
import scala.meta.internal.metals.config.BloopJvmProperties
import scala.meta.internal.metals.config.EclipseFormatConfig
import scala.meta.internal.metals.config.FallbackClasspathConfig
import scala.meta.internal.metals.config.FallbackSourcepathConfig
import scala.meta.internal.metals.config.JavacServicesOverrides
import scala.meta.internal.metals.config.MbtConfig
import scala.meta.internal.metals.config.TargetBuildTool
import scala.meta.internal.metals.config.TestUserInterfaceKind
import scala.meta.internal.metals.config.TurbineRecompileDelayConfig
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig.ScalaImportsPlacement

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
        UserConfiguration.fromJson(
          json,
          ClientConfiguration.default,
          jprops,
        )
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
    assertEquals(obtained.shimGlobs, Map.empty[String, List[String]])
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
    "shim-globs",
    """
      |{
      | "shim-globs": {
      |   "default": ["shims.scala", "**/shims/*.scala"],
      |   "db": ["**/db-shims/*.scala"]
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assertEquals(
      obtained.shimGlobs,
      Map(
        "default" -> List("shims.scala", "**/shims/*.scala"),
        "db" -> List("**/db-shims/*.scala"),
      ),
    )
  }

  checkError(
    "invalid shim-globs",
    """
      |{
      | "shim-globs": {
      |   "default": "shims.scala"
      | }
      |}
    """.stripMargin,
    """json error: key 'shim-globs' should have be object with array string values but obtained {"default":"shims.scala"}""",
  )

  checkError(
    "invalid workspace symbol provider",
    """
      |{
      | "workspace-symbol-provider": "invalid"
      |}
      |""".stripMargin,
    "Invalid workspace-symbol-provider 'invalid'. Valid values are: bsp, mbt",
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
    "eclipse-format-setting",
    """
      |{
      | "eclipseFormat": {
      |  "configPath": "path",
      |  "profile": "profile"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.eclipseFormat ==
        EclipseFormatConfig(Some(AbsolutePath("path")), Some("profile"))
    )
  }
  checkOK(
    "java format no setting",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.eclipseFormat == EclipseFormatConfig.default)
  }
  checkOK(
    "java format no profile setting",
    """
      |{
      | "eclipseFormat": {
      |  "configPath": "path"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.eclipseFormat == EclipseFormatConfig(
        Some(AbsolutePath("path")),
        None,
      )
    )
  }

  test("mbt feature flag") {
    val alwaysEnableMbtFeatureFlag = new FeatureFlagProvider {
      override def readBoolean(
          flag: FeatureFlag
      ): Optional[java.lang.Boolean] = {
        Optional.of(flag == FeatureFlag.MBT_WORKSPACE_SYMBOL_PROVIDER)
      }

      def readInt(flag: FeatureFlag, default: Integer): Optional[Integer] =
        Optional.empty()
    }

    // Assert that feature flag overrides when there is no custom setting
    val Right(obtained) = UserConfiguration.fromJson(
      UserConfiguration.parse("{}"),
      ClientConfiguration.default,
      featureFlags = alwaysEnableMbtFeatureFlag,
    )
    assertEquals(
      obtained.workspaceSymbolProvider,
      WorkspaceSymbolProviderConfig.MBT,
    )

    // Assert that a custom "bsp" setting overrides the feature flag
    val Right(obtained2) = UserConfiguration.fromJson(
      UserConfiguration.parse("""{
                                |  "workspaceSymbolProvider": "bsp"
                                |}""".stripMargin),
      ClientConfiguration.default,
      featureFlags = alwaysEnableMbtFeatureFlag,
    )
    assertEquals(
      obtained2.workspaceSymbolProvider,
      WorkspaceSymbolProviderConfig.BSP,
    )
  }

  checkOK(
    "mbt-references-timeout",
    """
      |{
      |  "mbt": {
      |    "referencesTimeoutSeconds": "5"
      |  }
      |}
      |""".stripMargin,
  ) { obtained =>
    assertEquals(obtained.mbt.referencesTimeoutSeconds, 5)
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
      javaFormatter = JavaFormatterConfig.Eclipse,
      symbolPrefixes = Map("java/util/" -> "hello."),
      shimGlobs = Map(
        "default" -> List("shims.scala", "**/shims/*.scala"),
        "db" -> List("**/db-shims/*.scala"),
      ),
      worksheetCancelTimeout = 10,
      bloopSbtAlreadyInstalled = true,
      bloopVersion = Some("1.2.3"),
      bloopJvmProperties =
        BloopJvmProperties.WithProperties(List("a", "b", "c")),
      superMethodLensesEnabled = true,
      inlayHints = InlayHintsOptions(
        hintsInPatternMatch = true,
        implicitArguments = true,
        inferredType = true,
        implicitConversions = true,
        typeParameters = true,
      ),
      enableStripMarginOnTypeFormatting = false,
      enableIndentOnPaste = true,
      excludedPackages = Some(List("excluded")),
      fallbackScalaVersion = Some("3.2.1"),
      fallbackClasspath = FallbackClasspathConfig.All3rdparty,
      fallbackSourcepath = FallbackSourcepathConfig.AllSources,
      testUserInterface = TestUserInterfaceKind.TestExplorer,
      eclipseFormat = EclipseFormatConfig(Some(fakePath), Some("profile")),
      javacServicesOverrides =
        JavacServicesOverrides.default.copy(names = false),
      scalafixRulesDependencies = List("rule1", "rule2"),
      customProjectRoot = Some("customs"),
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
      javaTurbineRecompileDelay = TurbineRecompileDelayConfig.testing,
      verboseCompilation = true,
      autoImportBuild = AutoImportBuildKind.All,
      scalaCliLauncher = Some("scala-cli"),
      scalaCliEnabled = true,
      defaultBspToBuildTool = true,
      additionalPcChecks = List("refchecks"),
      scalaImportsPlacement = ScalaImportsPlacement.SMART,
      batchSemanticdbCompilerInstances = 4,
      promptBuildImport = true,
      mbt = MbtConfig(false, true, 1000),
    )

    val json = nonDefault.toString()
    assertNoDiff(
      json,
      s"""{
  "javaHome": "/fake/home",
  "sbtScript": "sbt",
  "gradleScript": "gradle",
  "mavenScript": "mvn",
  "millScript": "mill",
  "scalafmtConfigPath": "$fakePathString",
  "scalafixConfigPath": "$fakePathString",
  "symbolPrefixes": {
    "java/util/": "hello."
  },
  "shimGlobs": {
    "default": [
      "shims.scala",
      "**/shims/*.scala"
    ],
    "db": [
      "**/db-shims/*.scala"
    ]
  },
  "scalafixRulesDependencies": [
    "rule1",
    "rule2"
  ],
  "scalafixLintEnabled": false,
  "excludedPackages": [
    "excluded"
  ],
  "bloopSbtAlreadyInstalled": true,
  "bloopVersion": "1.2.3",
  "bloopJvmProperties": [
    "a",
    "b",
    "c"
  ],
  "superMethodLensesEnabled": true,
  "gotoTestLensesEnabled": true,
  "inlayHints": {
    "inferredTypes": {
      "enable": false
    },
    "typeParameters": {
      "enable": false
    },
    "implicitArguments": {
      "enable": false
    },
    "hintsInPatternMatch": {
      "enable": false
    },
    "hintsXRayMode": {
      "enable": false
    },
    "namedParameters": {
      "enable": false
    },
    "implicitConversions": {
      "enable": false
    },
    "closingLabels": {
      "enable": false
    },
    "byNameParameters": {
      "enable": false
    }
  },
  "enableStripMarginOnTypeFormatting": false,
  "enableIndentOnPaste": true,
  "rangeFormattingProviders": [
    "scalafmt"
  ],
  "fallbackScalaVersion": "3.2.1",
  "worksheetCancelTimeout": 10,
  "testUserInterface": "test explorer",
  "eclipseFormat": {
    "configPath": "$fakePathString",
    "profile": "profile"
  },
  "javaFormatter": "eclipse",
  "scalaCliLauncher": "scala-cli",
  "scalaCliEnabled": true,
  "customProjectRoot": "customs",
  "verboseCompilation": true,
  "autoImportBuild": "all",
  "targetBuildTool": "none",
  "defaultBspToBuildTool": true,
  "presentationCompilerDiagnostics": true,
  "buildChangedAction": "none",
  "buildOnChange": false,
  "buildOnFocus": false,
  "useSourcePath": true,
  "workspaceSymbolProvider": "mbt",
  "definitionProviders": "all",
  "javaSymbolLoader": "turbine-classpath",
  "javaTurbineRecompileDelay": "100 milliseconds",
  "javaTurbineCache": false,
  "javacServicesOverrides": {
    "names": false,
    "attr": true,
    "typeEnter": true,
    "enter": true
  },
  "compilerProgress": "enabled",
  "referenceProvider": "mbt",
  "additionalPcChecks": [
    "refchecks"
  ],
  "scalaImportsPlacement": "smart",
  "batchSemanticdbCompilerInstances": 4,
  "promptBuildImport": true,
  "protobufLspEnabled": true,
  "enableBestEffort": false,
  "startMcpServer": false,
  "mbt": {
    "importGeneratedSources": false,
    "semanticdbCacheEnabled": true,
    "semanticdbCacheMaxSize": 1000,
    "referencesTimeoutSeconds": 20
  },
  "fallbackClasspath": "all3rdparty",
  "fallbackSourcepath": "allsources"
}""",
    )
    val roundtripJson = UserConfiguration.parse(json)

    val params = new InitializeParams()
    params.setInitializationOptions(
      Map("testExplorerProvider" -> true).asJava.toJsonObject
    )

    val clientConfig = ClientConfiguration(
      MetalsServerConfig.default,
      params,
      NoopFeatureFlagProvider,
    )

    val roundtrip = UserConfiguration
      .fromJson(
        roundtripJson,
        clientConfig,
      )
      .getOrElse(fail("Failed to parse roundtrip json"))
      // maps have a different order
      .copy(inlayHints = nonDefault.inlayHints)
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
          |scalafmt-config-path                         string                         .scalafmt.conf  Scalafmt config path
          |scalafix-config-path                         string                         .scalafix.conf  Scalafix config path
          |symbol-prefixes                              string                         {}              Symbol prefixes
          |shim-globs                                   string                         `{}`.           Shim file globs
          |scalafix-rules-dependencies                  array                          []              Scalafix rules dependencies
          |scalafix-lint-enabled                        boolean                        false           Enable Scalafix lint diagnostics
          |excluded-packages                            array                          []              Excluded Packages
          |bloop-sbt-already-installed                  boolean                        false           Don't generate Bloop plugin file for sbt
          |bloop-version                                string                         $bloopVersionPadded Version of Bloop
          |bloop-jvm-properties                         array                          ["-Xmx1G"]      Bloop JVM Properties
          |super-method-lenses-enabled                  boolean                        false           Should display lenses with links to super methods
          |goto-test-lenses-enabled                     boolean                        false           Enable goto-test lenses
          |inlay-hints.inferred-types.enable            boolean                        false           Should display type annotations for inferred types
          |inlay-hints.named-parameters.enable          boolean                        false           Should display parameter names next to arguments
          |inlay-hints.by-name-parameters.enable        boolean                        false           Should display if a parameter is by-name at usage sites
          |inlay-hints.implicit-arguments.enable        boolean                        false           Should display implicit parameter at usage sites
          |inlay-hints.implicit-conversions.enable      boolean                        false           Should display implicit conversion at usage sites
          |inlay-hints.type-parameters.enable           boolean                        false           Should display type annotations for type parameters
          |inlay-hints.hints-in-pattern-match.enable    boolean                        false           Should display type annotations in pattern matches
          |inlay-hints.hints-x-ray-mode.enable          boolean                        false           Should display type annotations for intermediate types of multi-line expressions
          |inlay-hints.closing-labels.enable            boolean                        false           Should display closing label hints for methods/classes/objects next to their closing braces
          |enable-strip-margin-on-type-formatting       boolean                        true            Enable strip margin on type formatting
          |enable-indent-on-paste                       boolean                        false           Indent snippets when pasted.
          |range-formatting-providers                   array                          ["scalafmt"]    Range formatting providers
          |fallback-scala-version                       string                         $scala3Padded Default fallback Scala version
          |worksheet-cancel-timeout                     number                         4               Worksheet cancel timeout
          |test-user-interface                          [code lenses,test explorer]    code lenses     Test UI used for tests and test suites
          |eclipse-format.config-path                   boolean                        None            Eclipse Java formatter config path
          |eclipse-format.profile                       boolean                        Some(GoogleStyle) Eclipse Java formatting profile
          |java-formatter                               [Eclipse,GoogleJavaFormat,None] GoogleJavaFormat Java formatter
          |scala-cli-launcher                           string                         ""              Scala CLI launcher
          |scala-cli-enabled                            boolean                        false           Enable Scala CLI
          |custom-project-root                          string                         ""              Custom project root
          |verbose-compilation                          boolean                        false           Show all compilation debugging information
          |auto-import-build                            [Off,Initial,All]              Off             Import build when changes detected without prompting
          |target-build-tool                            [sbt,gradle,mvn,mill,scala-cli,bazel,deder,none] none            Preferred build tool when multiple are detected
          |default-bsp-to-build-tool                    boolean                        false           Default to using build tool as your build server.
          |presentation-compiler-diagnostics            boolean                        true            [Experimental] Show diagnostics messages from the Scala presentation compiler
          |build-changed-action                         [None,Prompt]                  None            Build changed action
          |build-on-change                              boolean                        true            Disable build-on-change
          |build-on-focus                               boolean                        true            Enable or disable build-on-focus
          |preferred-build-server                       string                         empty string `""`. Preferred build server
          |use-source-path                              boolean                        true            Use presentation compiler source path
          |workspace-symbol-provider                    [bsp,mbt]                      mbt             Workspace Symbol Provider
          |definition-providers                         [MBT,Protobuf,All]             All             Definition providers
          |java-symbol-loader                           [turbine-classpath,javac-sourcepath] turbine-classpath Java symbol loader
          |java-turbine-recompile-delay                 string                         ""              Java turbine recompile delay
          |java-turbine-cache                           boolean                        false           Java turbine cache
          |javac-services-overrides.names               boolean                        true            Override names
          |javac-services-overrides.attr                boolean                        true            Override attr
          |javac-services-overrides.type-enter          boolean                        true            Override type enter
          |javac-services-overrides.enter               boolean                        true            Override enter
          |compiler-progress                            [Enabled,Disabled]             Enabled         Compiler progress
          |reference-provider                           [BSP,MBT]                      MBT             Reference provider
          |additional-pc-checks                         array                          `[]`            Additional presentation compiler checks to run
          |scala-imports-placement                      [APPEND_LAST,SMART]            SMART           Scala imports placement
          |batch-semanticdb-compiler-instances          number                         1               Batch semanticdb compiler instances
          |prompt-build-import                          boolean                        false           Prompt Build Import
          |protobuf-lsp-enabled                         boolean                        true            Enabled Protobuf LSP
          |enable-best-effort                           boolean                        false           Use best effort compilation for Scala 3.
          |default-shell                                string                         ""              Full path to the shell executable to be used as the default
          |start-mcp-server                             boolean                        false           Start MCP server
          |mcp-client                                   string                         ""              MCP Client Name
          |mbt.import-generated-sources                 boolean                        false           Import build tool generated sources.
          |mbt.semanticdb-cache-enabled                 boolean                        false           Enable filesystem-based MBT Semanticdb cache
          |mbt.semanticdb-cache-max-size                number                         2147483647      Semanticdb cache maximum size
          |mbt.referencesTimeoutSeconds                 number                         20              Number of seconds to wait for references operations in MBT mode
          |fallback-classpath                           [All3rdparty,Guessed,Mbt,Default,None] Default         Fallback classpath
          |fallback-sourcepath                          [AllSources,None]              AllSources      Fallback sourcepath""".stripMargin
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
    assert(obtained.targetBuildTool == TargetBuildTool.Bazel)
  }

  checkOK(
    "target-build-tool-unset",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool == TargetBuildTool.None)
  }

  checkOK(
    "target-build-tool-empty-string",
    """
      |{
      | "target-build-tool": ""
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool == TargetBuildTool.None)
  }

  checkError(
    "target-build-tool-invalid",
    """
      |{
      | "target-build-tool": "invalid-tool"
      |}
    """.stripMargin,
    "Invalid target-build-tool 'invalid-tool'. Valid values are: sbt, gradle, mvn, mill, scala-cli, bazel, deder, none",
  )

  checkOK(
    "target-build-tool-all-valid-values",
    """
      |{
      | "target-build-tool": "sbt"
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.targetBuildTool == TargetBuildTool.Sbt)
  }

  checkOK(
    "mbt-partly-defined",
    """
      |{
      | "mbt": {
      |   "importGeneratedSources": true
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.mbt.importGeneratedSources == true)
    assert(obtained.mbt.semanticdbCacheEnabled == false)
    assert(obtained.mbt.semanticdbCacheMaxSize == Int.MaxValue)
  }

  checkOK(
    "mbt-config-new-format",
    """
      |{
      | "mbt": {
      |   "importGeneratedSources": true,
      |   "semanticdbCacheEnabled": true,
      |   "semanticdbCacheMaxSize": 500
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.mbt.importGeneratedSources == true)
    assert(obtained.mbt.semanticdbCacheEnabled == true)
    assert(obtained.mbt.semanticdbCacheMaxSize == 500)
  }

  checkError(
    "mbt-invalid-subfield",
    """
      |{
      | "mbt": {
      |   "semanticdbCacheMaxSize": "invalid"
      | }
      |}
    """.stripMargin,
    "Not a number: 'invalid'",
  )
}
