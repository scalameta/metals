$version: "2"

namespace scala.meta.internal.telemetry

use alloy#simpleRestJson
use smithy4s.meta#packedInputs

@packedInputs
@simpleRestJson
service TelemetryService {
    version: "1.0.0"
    operations: [SendReportEvent]
}

@http(method: "POST", uri: "/v1/telemetry/report", code: 200)
operation SendReportEvent {
    input: ReportEvent
}

structure ReportEvent {
    @required name: String
    @required text: String
    @required shortSummary: String
    id: String
    error: ReportedError
    @required reporterName: String // Reporter.name
    @required reporterContext: ReporterContext
    @required env: Environment
}

list Exceptions {member: String}
structure ReportedError {
    @required exceptions: Exceptions
    @required stacktrace: String
}

// ------------------
// Environment
// ------------------
structure Environment {
    @required java: JavaInfo
    @required system: SystemInfo
}

structure JavaInfo {
    @required version: String
    distribution: String
}

structure SystemInfo {
    @required architecture: String
    @required name: String
    @required version: String
}

// ------------------
// Reporter Context
// ------------------
union ReporterContext {
    metalsLsp: MetalsLspContext
    scalaPresentationCompiler: ScalaPresentationCompilerContext
    unknown: UnknownProducerContext
}

structure MetalsLspContext {
    @required metalsVersion: String
    @required userConfig: MetalsUserConfiguration
    @required serverConfig: MetalsServerConfiguration,
    @required buildServerConnections: BuildServerConnections
}

list BuildServerConnections {member: BuildServerConnection}
structure BuildServerConnection{
    @required name: String,
    @required version: String
    @required isMain: Boolean
}

list JVMProperties { member: String }
list ExcludedPackages{ member: String }

structure MetalsUserConfiguration{
    @required symbolPrefixes: SymbolPrefixes,
    @required bloopSbtAlreadyInstalled: Boolean,
    bloopVersion: String,
    bloopJvmProperties: JVMProperties,
    ammoniteJvmProperties: JVMProperties,
    @required superMethodLensesEnabled: Boolean,
    showInferredType: String,
    @required showImplicitArguments: Boolean,
    @required showImplicitConversionsAndClasses: Boolean,
    remoteLanguageServer: String,
    @required enableStripMarginOnTypeFormatting: Boolean,
    @required enableIndentOnPaste: Boolean,
    @required enableSemanticHighlighting: Boolean,
    excludedPackages: ExcludedPackages,
    fallbackScalaVersion: String,
    @required testUserInterface: TestUserInterfaceKind,
}

structure MetalsServerConfiguration{
    @required executeClientCommand: String,
    @required snippetAutoIndent: Boolean,
    @required isHttpEnabled: Boolean,
    @required isInputBoxEnabled: Boolean ,
    @required askToReconnect: Boolean,
    @required allowMultilineStringFormatting: Boolean,
    @required compilers: PresentationCompilerConfig
}

intEnum TestUserInterfaceKind {
    TEST_EXPLOLER = 1
    CODE_LENSES = 2
}

// Presentation compiler
structure ScalaPresentationCompilerContext {
    @required scalaVersion: String
    @required options: PresentationCompilerOptions
    @required config: PresentationCompilerConfig
}

structure PresentationCompilerConfig{
    @required symbolPrefixes: SymbolPrefixes,
    completionCommand: String,
    parameterHintsCommand: String,
    @required overrideDefFormat: String
    @required isDefaultSymbolPrefixes: Boolean
    @required isCompletionItemDetailEnabled: Boolean,
    @required isStripMarginOnTypeFormattingEnabled: Boolean,
    @required isCompletionItemDocumentationEnabled: Boolean,
    @required isHoverDocumentationEnabled: Boolean,
    @required snippetAutoIndent: Boolean,
    @required isSignatureHelpDocumentationEnabled: Boolean,
    @required isCompletionSnippetsEnabled: Boolean,
    @required semanticdbCompilerOptions: SemanticdbCompilerOptions
}

map SymbolPrefixes { key: String, value: String }
list PresentationCompilerOptions { member: String }
list SemanticdbCompilerOptions{ member: String }

// Other
structure UnknownProducerContext {
    @required name: String
    @required version: String
    @required properties: ProducerProperties
}

map ProducerProperties { key: String, value: String }




