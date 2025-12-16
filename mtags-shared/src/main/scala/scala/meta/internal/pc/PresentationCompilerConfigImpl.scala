package scala.meta.internal.pc

import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.Optional
import java.util.concurrent.TimeUnit

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.ContentType
import scala.meta.pc.JavacServicesOverridesConfig
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.meta.pc.SourcePathMode

case class PresentationCompilerConfigImpl(
    debug: Boolean = false,
    _parameterHintsCommand: Option[String] = None,
    _completionCommand: Option[String] = None,
    _symbolPrefixes: collection.Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala,
    overrideDefFormat: OverrideDefFormat = OverrideDefFormat.Ascii,
    isCompletionItemDetailEnabled: Boolean = true,
    isCompletionItemDocumentationEnabled: Boolean = true,
    isHoverDocumentationEnabled: Boolean = true,
    snippetAutoIndent: Boolean = true,
    isSignatureHelpDocumentationEnabled: Boolean = true,
    isCompletionSnippetsEnabled: Boolean = true,
    override val isDetailIncludedInLabel: Boolean = true,
    isCompletionItemResolve: Boolean = true,
    _isStripMarginOnTypeFormattingEnabled: () => Boolean = () => true,
    timeoutDelay: Long = 20,
    timeoutUnit: TimeUnit =
      if (sys.props.contains("metals.debug")) TimeUnit.MINUTES
      else TimeUnit.SECONDS,
    semanticdbCompilerOptions: util.List[String] =
      PresentationCompilerConfig.defaultSemanticdbCompilerOptions(),
    override val hoverContentType: ContentType = ContentType.MARKDOWN,
    override val emitDiagnostics: Boolean = false,
    override val workspaceRoot: Path =
      Paths.get(System.getProperty("user.dir")),
    override val sourcePathMode: SourcePathMode = SourcePathMode.PRUNED,
    override val shouldRunRefchecks: Boolean = false,
    override val javacServicesOverrides: JavacServicesOverridesConfig =
      JavacServicesOverridesConfig.EMPTY
) extends PresentationCompilerConfig {

  override def isStripMarginOnTypeFormattingEnabled(): Boolean =
    _isStripMarginOnTypeFormattingEnabled()
  override def symbolPrefixes(): util.Map[String, String] =
    _symbolPrefixes.asJava
  override def parameterHintsCommand: Optional[String] =
    Optional.ofNullable(_parameterHintsCommand.orNull)
  override def completionCommand: Optional[String] =
    Optional.ofNullable(_completionCommand.orNull)

  override val isDefaultSymbolPrefixes: Boolean =
    _symbolPrefixes == PresentationCompilerConfig
      .defaultSymbolPrefixes()
      .asScala

  /**
   * Used to update the compiler config after we recieve the InitializationOptions.
   * If the user sets a value in the InitializationOptions, then the value will be
   * captures and set here. If not, we just resort back to the default that already
   * exists.
   */
  def update(
      options: CompilerInitializationOptions
  ): PresentationCompilerConfigImpl =
    copy(
      isCompletionItemDetailEnabled = options.isCompletionItemDetailEnabled
        .getOrElse(this.isCompletionItemDetailEnabled),
      isCompletionItemDocumentationEnabled =
        options.isCompletionItemDocumentationEnabled.getOrElse(
          this.isCompletionItemDocumentationEnabled
        ),
      isHoverDocumentationEnabled = options.isHoverDocumentationEnabled
        .getOrElse(this.isHoverDocumentationEnabled),
      snippetAutoIndent =
        options.snippetAutoIndent.getOrElse(this.snippetAutoIndent),
      isSignatureHelpDocumentationEnabled =
        options.isSignatureHelpDocumentationEnabled.getOrElse(
          this.isSignatureHelpDocumentationEnabled
        ),
      isCompletionItemResolve = options.isCompletionItemResolve.getOrElse(
        this.isCompletionItemResolve
      ),
      isDetailIncludedInLabel = options.isDetailIncludedInLabel.getOrElse(
        this.isDetailIncludedInLabel
      ),
      _parameterHintsCommand = options.parameterHintsCommand.orElse(
        this._parameterHintsCommand
      ),
      _completionCommand = options.completionCommand.orElse(
        this._completionCommand
      ),
      overrideDefFormat = options.overrideDefFormat
        .flatMap {
          case "unicode" => Some(OverrideDefFormat.Unicode)
          case "ascii" => Some(OverrideDefFormat.Ascii)
          case _ => None
        }
        .getOrElse(this.overrideDefFormat)
    )
}
