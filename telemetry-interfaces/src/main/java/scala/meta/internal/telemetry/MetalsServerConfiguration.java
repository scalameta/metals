package scala.meta.internal.telemetry;

public class MetalsServerConfiguration {
	final private String executeClientCommand;
	final private boolean snippetAutoIndent;
	final private boolean isHttpEnabled;
	final private boolean isInputBoxEnabled;
	final private boolean askToReconnect;
	final private boolean allowMultilineStringFormatting;
	final private PresentationCompilerConfig compilers;

	public MetalsServerConfiguration(String executeClientCommand, boolean snippetAutoIndent, boolean isHttpEnabled,
			boolean isInputBoxEnabled, boolean askToReconnect, boolean allowMultilineStringFormatting,
			PresentationCompilerConfig compilers) {
		this.executeClientCommand = executeClientCommand;
		this.snippetAutoIndent = snippetAutoIndent;
		this.isHttpEnabled = isHttpEnabled;
		this.isInputBoxEnabled = isInputBoxEnabled;
		this.askToReconnect = askToReconnect;
		this.allowMultilineStringFormatting = allowMultilineStringFormatting;
		this.compilers = compilers;
	}

	public String getExecuteClientCommand() {
		return executeClientCommand;
	}

	public boolean isSnippetAutoIndent() {
		return snippetAutoIndent;
	}

	public boolean isHttpEnabled() {
		return isHttpEnabled;
	}

	public boolean isInputBoxEnabled() {
		return isInputBoxEnabled;
	}

	public boolean isAskToReconnect() {
		return askToReconnect;
	}

	public boolean isAllowMultilineStringFormatting() {
		return allowMultilineStringFormatting;
	}

	public PresentationCompilerConfig getCompilers() {
		return compilers;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((executeClientCommand == null) ? 0 : executeClientCommand.hashCode());
		result = prime * result + (snippetAutoIndent ? 1231 : 1237);
		result = prime * result + (isHttpEnabled ? 1231 : 1237);
		result = prime * result + (isInputBoxEnabled ? 1231 : 1237);
		result = prime * result + (askToReconnect ? 1231 : 1237);
		result = prime * result + (allowMultilineStringFormatting ? 1231 : 1237);
		result = prime * result + ((compilers == null) ? 0 : compilers.hashCode());
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
		MetalsServerConfiguration other = (MetalsServerConfiguration) obj;
		if (executeClientCommand == null) {
			if (other.executeClientCommand != null)
				return false;
		} else if (!executeClientCommand.equals(other.executeClientCommand))
			return false;
		if (snippetAutoIndent != other.snippetAutoIndent)
			return false;
		if (isHttpEnabled != other.isHttpEnabled)
			return false;
		if (isInputBoxEnabled != other.isInputBoxEnabled)
			return false;
		if (askToReconnect != other.askToReconnect)
			return false;
		if (allowMultilineStringFormatting != other.allowMultilineStringFormatting)
			return false;
		if (compilers == null) {
			if (other.compilers != null)
				return false;
		} else if (!compilers.equals(other.compilers))
			return false;
		return true;
	}

}