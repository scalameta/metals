package tests

import java.nio.file.Paths
import java.util.Optional
import java.util.Properties

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.BloopJvmProperties
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Configs.AdditionalPcChecksConfig
import scala.meta.internal.metals.Configs.BatchSemanticdbConfig
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.JavacServicesOverrides
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.InlayHintsOption
import scala.meta.internal.metals.InlayHintsOptions
import scala.meta.internal.metals.JavaFormatConfig
import scala.meta.internal.metals.JavaFormatterConfig
import scala.meta.internal.metals.JavaLintOptions
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
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
    "java-lint-options-invalid",
    """
      |{
      | "java-lint-options": ["deprecation", "typo"]
      |}
    """.stripMargin,
    "json error: invalid config value 'typo' for javaLintOptions. Valid values are " +
      "\"cast\", \"dep-ann\", \"deprecation\", \"divzero\", \"empty\", \"fallthrough\", \"finally\", " +
      "\"lossy-conversions\", \"overloads\", \"overrides\", \"preview\", \"rawtypes\", \"removal\", " +
      "\"serial\", \"static\", \"strictfp\", \"synchronization\", \"text-blocks\", \"this-escape\", " +
      "\"try\", \"unchecked\", \"varargs\"",
  )

  checkOK(
    "java-lint-options-empty",
    """
      |{
      | "java-lint-options": []
      |}
    """.stripMargin,
  ) { obtained =>
    assertEquals(obtained.javaLintOptions, JavaLintOptions(Nil))
  }

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
    "json error: invalid config value 'invalid' for workspaceSymbolProvider. Valid values are \"bsp\" and \"mbt\"",
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
      WorkspaceSymbolProviderConfig("mbt"),
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
      WorkspaceSymbolProviderConfig("bsp"),
    )
  }

  checkOK(
    "protobuf package prefix",
    """
      |{
      |  "protobufLsp": {
      |    "definition": true,
      |    "javaPackagePrefix": "grpc_shaded."
      |  }
      |}
      |""".stripMargin,
  ) { obtained =>
    assertEquals(obtained.protobufLspConfig.definition, true)
    assertEquals(
      obtained.protobufLspConfig.javaPackagePrefix,
      "grpc_shaded.",
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
      javaFormatter = Some(JavaFormatterConfig("eclipse")),
      symbolPrefixes = Map("java/util/" -> "hello."),
      shimGlobs = Map(
        "default" -> List("shims.scala", "**/shims/*.scala"),
        "db" -> List("**/db-shims/*.scala"),
      ),
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
      fallbackClasspath = FallbackClasspathConfig.all3rdparty,
      fallbackSourcepath = FallbackSourcepathConfig("all-sources"),
      testUserInterface = TestUserInterfaceKind.TestExplorer,
      javaFormatConfig = Some(JavaFormatConfig(fakePath, Some("profile"))),
      javacServicesOverrides =
        JavacServicesOverrides.default.copy(names = false),
      scalafixRulesDependencies = List("rule1", "rule2"),
      javaLintOptions = JavaLintOptions(Nil),
      customProjectRoot = Some("customs"),
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig("mbt"),
      javaTurbineRecompileDelay = TurbineRecompileDelayConfig.testing,
      verboseCompilation = true,
      automaticImportBuild = AutoImportBuildKind.All,
      scalaCliLauncher = Some("scala-cli"),
      scalaCliEnabled = true,
      defaultBspToBuildTool = true,
      additionalPcChecks = AdditionalPcChecksConfig(List("refchecks")),
      scalaImportsPlacement = ScalaImportsPlacement.SMART,
      batchSemanticdbCompilerInstances = BatchSemanticdbConfig(4),
      promptBuildImport = true,
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
  "worksheetScreenWidth": 140,
  "worksheetCancelTimeout": 10,
  "bloopSbtAlreadyInstalled": true,
  "bloopVersion": "1.2.3",
  "bloopJvmProperties": [
    "a",
    "b",
    "c"
  ],
  "superMethodLensesEnabled": true,
  "gotoTestLensesEnabled": true,
  "inlayHintsOptions": {
    "HintsInPatternMatch": "true",
    "ImplicitArguments": "true",
    "TypeParameters": "true",
    "InferredType": "true",
    "ImplicitConversions": "true"
  },
  "enableStripMarginOnTypeFormatting": false,
  "enableIndentOnPaste": true,
  "rangeFormattingProviders": [
    "scalafmt"
  ],
  "enableSemanticHighlighting": false,
  "excludedPackages": [
    "excluded"
  ],
  "fallbackScalaVersion": "3.2.1",
  "fallbackClasspath": [
    "all-3rdparty"
  ],
  "fallbackSourcepath": "all-sources",
  "testUserInterface": "test explorer",
  "javaFormat": {
    "eclipseConfigPath": "$fakePathString",
    "eclipseProfile": "profile"
  },
  "javaFormatter": "eclipse",
  "scalafixRulesDependencies": [
    "rule1",
    "rule2"
  ],
  "scalafixLintEnabled": false,
  "javaLintOptions": [],
  "customProjectRoot": "customs",
  "verboseCompilation": true,
  "autoImportBuilds": "all",
  "scalaCliLauncher": "scala-cli",
  "scalaCliEnabled": true,
  "defaultBspToBuildTool": true,
  "presentationCompilerDiagnostics": true,
  "buildChangedAction": "none",
  "buildOnChange": false,
  "buildOnFocus": false,
  "useSourcePath": true,
  "workspaceSymbolProvider": "mbt",
  "definitionProviders": [
    "mbt",
    "protobuf"
  ],
  "definitionIndexStrategy": "classpath",
  "protoOutlineProvider": "v1",
  "javaSymbolLoader": "turbine-classpath",
  "javaTurbineRecompileDelay": "100 milliseconds",
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
  "protobufLsp": {
    "hover": false,
    "semanticdb": false,
    "diagnostics": false,
    "definition": false,
    "javaPackagePrefix": "",
    "completions": false,
    "semanticTokens": false
  },
  "enableBestEffort": false,
  "startMcpServer": false,
  "importGeneratedSourcesMbt": false
}""",
    )
    val roundtripJson = UserConfiguration.parse(json)

    val params = new InitializeParams()
    params.setInitializationOptions(
      Map("testExplorerProvider" -> true).asJava.toJsonObject
    )

    val clientConfig = ClientConfiguration(MetalsServerConfig.default, params)

    val roundtrip = UserConfiguration
      .fromJson(
        roundtripJson,
        clientConfig,
      )
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
          |shim-globs                                   string                         `{}`.           Shim file globs
          |scalafix-rules-dependencies                  array                          []              Scalafix rules dependencies
          |scalafix-lint-enabled                        boolean                        false           Enable Scalafix lint diagnostics
          |java-lint-options                            array                          [cast,deprecation,dep-ann,divzero,empty,fallthrough,finally,lossy-conversions,overloads,overrides,rawtypes,removal,serial,static,strictfp,synchronization,text-blocks,this-escape,try,unchecked,varargs] Java lint diagnostics
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
          |java-formatter                               string                         empty string `""`. Java formatter
          |scala-cli-launcher                           string                         ""              Scala CLI launcher
          |custom-project-root                          string                         ""              Custom project root
          |verbose-compilation                          boolean                        false           Show all compilation debugging information
          |auto-import-builds                           [off,initial,all]              off             Import build when changes detected without prompting
          |target-build-tool                            string                         ""              Preferred build tool when multiple are detected
          |default-bsp-to-build-tool                    boolean                        false           Default to using build tool as your build server.
          |presentation-compiler-diagnostics            boolean                        true            [Experimental] Show diagnostics messages from the Scala presentation compiler
          |build-on-change                              boolean                        true            Disable build-on-change
          |build-on-focus                               boolean                        true            Disable build-on-focus
          |preferred-build-server                       string                         empty string `""`. Preferred build server
          |use-source-path                              boolean                        true            Use presentation compiler source path
          |workspace-symbol-provider                    string                         bsp             Workspace Symbol Provider
          |additional-pc-checks                         array                          `[]`            Additional Presentation Compiler Checks
          |prompt-build-import                          boolean                        false           Prompt Build Import
          |enable-best-effort                           boolean                        false           Use best effort compilation for Scala 3.
          |default-shell                                string                         ""              Full path to the shell executable to be used as the default
          |start-mcp-server                             boolean                        false           Start MCP server
          |mcp-client                                   string                         ""              MCP Client Name
          |import-generated-sources-mbt                 boolean                        false           Import Generated Sources In MBT Builds""".stripMargin
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
    "Invalid target-build-tool 'invalid-tool'. Valid values are: bazel, deder, gradle, mill, mvn, sbt, scala-cli",
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
