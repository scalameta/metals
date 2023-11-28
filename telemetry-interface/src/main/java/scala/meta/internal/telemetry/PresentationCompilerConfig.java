package scala.meta.internal.telemetry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PresentationCompilerConfig {
	final private Map<String, String> symbolPrefixes;
	final private Optional<String> completionCommand;
	final private Optional<String> parameterHintsCommand;
	final private String overrideDefFormat;
	final private boolean isDefaultSymbolPrefixes;
	final private boolean isCompletionItemDetailEnabled;
	final private boolean isStripMarginOnTypeFormattingEnabled;
	final private boolean isCompletionItemDocumentationEnabled;
	final private boolean isHoverDocumentationEnabled;
	final private boolean snippetAutoIndent;
	final private boolean isSignatureHelpDocumentationEnabled;
	final private boolean isCompletionSnippetsEnabled;
	final private List<String> semanticdbCompilerOptions;

	public PresentationCompilerConfig(Map<String, String> symbolPrefixes, Optional<String> completionCommand,
			Optional<String> parameterHintsCommand, String overrideDefFormat, boolean isDefaultSymbolPrefixes,
			boolean isCompletionItemDetailEnabled, boolean isStripMarginOnTypeFormattingEnabled,
			boolean isCompletionItemDocumentationEnabled, boolean isHoverDocumentationEnabled,
			boolean snippetAutoIndent, boolean isSignatureHelpDocumentationEnabled, boolean isCompletionSnippetsEnabled,
			List<String> semanticdbCompilerOptions) {
		this.symbolPrefixes = symbolPrefixes;
		this.completionCommand = completionCommand;
		this.parameterHintsCommand = parameterHintsCommand;
		this.overrideDefFormat = overrideDefFormat;
		this.isDefaultSymbolPrefixes = isDefaultSymbolPrefixes;
		this.isCompletionItemDetailEnabled = isCompletionItemDetailEnabled;
		this.isStripMarginOnTypeFormattingEnabled = isStripMarginOnTypeFormattingEnabled;
		this.isCompletionItemDocumentationEnabled = isCompletionItemDocumentationEnabled;
		this.isHoverDocumentationEnabled = isHoverDocumentationEnabled;
		this.snippetAutoIndent = snippetAutoIndent;
		this.isSignatureHelpDocumentationEnabled = isSignatureHelpDocumentationEnabled;
		this.isCompletionSnippetsEnabled = isCompletionSnippetsEnabled;
		this.semanticdbCompilerOptions = semanticdbCompilerOptions;
	}

	public Map<String, String> getSymbolPrefixes() {
		return symbolPrefixes;
	}

	public Optional<String> getCompletionCommand() {
		return completionCommand;
	}

	public Optional<String> getParameterHintsCommand() {
		return parameterHintsCommand;
	}

	public String getOverrideDefFormat() {
		return overrideDefFormat;
	}

	public boolean isDefaultSymbolPrefixes() {
		return isDefaultSymbolPrefixes;
	}

	public boolean isCompletionItemDetailEnabled() {
		return isCompletionItemDetailEnabled;
	}

	public boolean isStripMarginOnTypeFormattingEnabled() {
		return isStripMarginOnTypeFormattingEnabled;
	}

	public boolean isCompletionItemDocumentationEnabled() {
		return isCompletionItemDocumentationEnabled;
	}

	public boolean isHoverDocumentationEnabled() {
		return isHoverDocumentationEnabled;
	}

	public boolean isSnippetAutoIndent() {
		return snippetAutoIndent;
	}

	public boolean isSignatureHelpDocumentationEnabled() {
		return isSignatureHelpDocumentationEnabled;
	}

	public boolean isCompletionSnippetsEnabled() {
		return isCompletionSnippetsEnabled;
	}

	public List<String> getSemanticdbCompilerOptions() {
		return semanticdbCompilerOptions;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((symbolPrefixes == null) ? 0 : symbolPrefixes.hashCode());
		result = prime * result + ((completionCommand == null) ? 0 : completionCommand.hashCode());
		result = prime * result + ((parameterHintsCommand == null) ? 0 : parameterHintsCommand.hashCode());
		result = prime * result + ((overrideDefFormat == null) ? 0 : overrideDefFormat.hashCode());
		result = prime * result + (isDefaultSymbolPrefixes ? 1231 : 1237);
		result = prime * result + (isCompletionItemDetailEnabled ? 1231 : 1237);
		result = prime * result + (isStripMarginOnTypeFormattingEnabled ? 1231 : 1237);
		result = prime * result + (isCompletionItemDocumentationEnabled ? 1231 : 1237);
		result = prime * result + (isHoverDocumentationEnabled ? 1231 : 1237);
		result = prime * result + (snippetAutoIndent ? 1231 : 1237);
		result = prime * result + (isSignatureHelpDocumentationEnabled ? 1231 : 1237);
		result = prime * result + (isCompletionSnippetsEnabled ? 1231 : 1237);
		result = prime * result + ((semanticdbCompilerOptions == null) ? 0 : semanticdbCompilerOptions.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		PresentationCompilerConfig other = (PresentationCompilerConfig) obj;
		if (symbolPrefixes == null) {
			if (other.symbolPrefixes != null)
				return false;
		} else if (!symbolPrefixes.equals(other.symbolPrefixes))
			return false;
		if (completionCommand == null) {
			if (other.completionCommand != null)
				return false;
		} else if (!completionCommand.equals(other.completionCommand))
			return false;
		if (parameterHintsCommand == null) {
			if (other.parameterHintsCommand != null)
				return false;
		} else if (!parameterHintsCommand.equals(other.parameterHintsCommand))
			return false;
		if (overrideDefFormat == null) {
			if (other.overrideDefFormat != null)
				return false;
		} else if (!overrideDefFormat.equals(other.overrideDefFormat))
			return false;
		if (isDefaultSymbolPrefixes != other.isDefaultSymbolPrefixes)
			return false;
		if (isCompletionItemDetailEnabled != other.isCompletionItemDetailEnabled)
			return false;
		if (isStripMarginOnTypeFormattingEnabled != other.isStripMarginOnTypeFormattingEnabled)
			return false;
		if (isCompletionItemDocumentationEnabled != other.isCompletionItemDocumentationEnabled)
			return false;
		if (isHoverDocumentationEnabled != other.isHoverDocumentationEnabled)
			return false;
		if (snippetAutoIndent != other.snippetAutoIndent)
			return false;
		if (isSignatureHelpDocumentationEnabled != other.isSignatureHelpDocumentationEnabled)
			return false;
		if (isCompletionSnippetsEnabled != other.isCompletionSnippetsEnabled)
			return false;
		if (semanticdbCompilerOptions == null) {
			if (other.semanticdbCompilerOptions != null)
				return false;
		} else if (!semanticdbCompilerOptions.equals(other.semanticdbCompilerOptions))
			return false;
		return true;
	}

}