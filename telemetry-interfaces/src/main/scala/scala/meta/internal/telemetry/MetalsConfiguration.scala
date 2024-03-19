package scala.meta.internal.telemetry

case class MetalsUserConfiguration(
    symbolPrefixes: Map[String, String],
    bloopSbtAlreadyInstalled: Boolean,
    bloopVersion: Option[String],
    bloopJvmProperties: List[String],
    ammoniteJvmProperties: List[String],
    superMethodLensesEnabled: Boolean,
    showInferredType: Option[String],
    showImplicitArguments: Boolean,
    showImplicitConversionsAndClasses: Boolean,
    enableStripMarginOnTypeFormatting: Boolean,
    enableIndentOnPaste: Boolean,
    enableSemanticHighlighting: Boolean,
    excludedPackages: List[String],
    fallbackScalaVersion: Option[String],
    testUserInterface: String,
)

case class MetalsServerConfiguration(
    executeClientCommand: String,
    snippetAutoIndent: Boolean,
    isHttpEnabled: Boolean,
    isInputBoxEnabled: Boolean,
    askToReconnect: Boolean,
    allowMultilineStringFormatting: Boolean,
    compilers: PresentationCompilerConfig,
)
