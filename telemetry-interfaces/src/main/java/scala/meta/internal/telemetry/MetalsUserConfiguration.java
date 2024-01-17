package scala.meta.internal.telemetry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MetalsUserConfiguration {
	final private Map<String, String> symbolPrefixes;
	final private boolean bloopSbtAlreadyInstalled;
	final private Optional<String> bloopVersion;
	final private List<String> bloopJvmProperties;
	final private List<String> ammoniteJvmProperties;
	final private boolean superMethodLensesEnabled;
	final private Optional<String> showInferredType;
	final private boolean showImplicitArguments;
	final private boolean showImplicitConversionsAndClasses;
	final private boolean enableStripMarginOnTypeFormatting;
	final private boolean enableIndentOnPaste;
	final private boolean enableSemanticHighlighting;
	final private List<String> excludedPackages;
	final private Optional<String> fallbackScalaVersion;
	final private String testUserInterface;

	public MetalsUserConfiguration(Map<String, String> symbolPrefixes, boolean bloopSbtAlreadyInstalled,
			Optional<String> bloopVersion, List<String> bloopJvmProperties, List<String> ammoniteJvmProperties,
			boolean superMethodLensesEnabled, Optional<String> showInferredType, boolean showImplicitArguments,
			boolean showImplicitConversionsAndClasses, boolean enableStripMarginOnTypeFormatting,
			boolean enableIndentOnPaste, boolean enableSemanticHighlighting, List<String> excludedPackages,
			Optional<String> fallbackScalaVersion, String testUserInterface) {
		this.symbolPrefixes = symbolPrefixes;
		this.bloopSbtAlreadyInstalled = bloopSbtAlreadyInstalled;
		this.bloopVersion = bloopVersion;
		this.bloopJvmProperties = bloopJvmProperties;
		this.ammoniteJvmProperties = ammoniteJvmProperties;
		this.superMethodLensesEnabled = superMethodLensesEnabled;
		this.showInferredType = showInferredType;
		this.showImplicitArguments = showImplicitArguments;
		this.showImplicitConversionsAndClasses = showImplicitConversionsAndClasses;
		this.enableStripMarginOnTypeFormatting = enableStripMarginOnTypeFormatting;
		this.enableIndentOnPaste = enableIndentOnPaste;
		this.enableSemanticHighlighting = enableSemanticHighlighting;
		this.excludedPackages = excludedPackages;
		this.fallbackScalaVersion = fallbackScalaVersion;
		this.testUserInterface = testUserInterface;
	}

	public Map<String, String> getSymbolPrefixes() {
		return symbolPrefixes;
	}

	public boolean isBloopSbtAlreadyInstalled() {
		return bloopSbtAlreadyInstalled;
	}

	public Optional<String> getBloopVersion() {
		return bloopVersion;
	}

	public List<String> getBloopJvmProperties() {
		return bloopJvmProperties;
	}

	public List<String> getAmmoniteJvmProperties() {
		return ammoniteJvmProperties;
	}

	public boolean isSuperMethodLensesEnabled() {
		return superMethodLensesEnabled;
	}

	public Optional<String> getShowInferredType() {
		return showInferredType;
	}

	public boolean isShowImplicitArguments() {
		return showImplicitArguments;
	}

	public boolean isShowImplicitConversionsAndClasses() {
		return showImplicitConversionsAndClasses;
	}

	public boolean isEnableStripMarginOnTypeFormatting() {
		return enableStripMarginOnTypeFormatting;
	}

	public boolean isEnableIndentOnPaste() {
		return enableIndentOnPaste;
	}

	public boolean isEnableSemanticHighlighting() {
		return enableSemanticHighlighting;
	}

	public List<String> getExcludedPackages() {
		return excludedPackages;
	}

	public Optional<String> getFallbackScalaVersion() {
		return fallbackScalaVersion;
	}

	public String getTestUserInterface() {
		return testUserInterface;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((symbolPrefixes == null) ? 0 : symbolPrefixes.hashCode());
		result = prime * result + (bloopSbtAlreadyInstalled ? 1231 : 1237);
		result = prime * result + ((bloopVersion == null) ? 0 : bloopVersion.hashCode());
		result = prime * result + ((bloopJvmProperties == null) ? 0 : bloopJvmProperties.hashCode());
		result = prime * result + ((ammoniteJvmProperties == null) ? 0 : ammoniteJvmProperties.hashCode());
		result = prime * result + (superMethodLensesEnabled ? 1231 : 1237);
		result = prime * result + ((showInferredType == null) ? 0 : showInferredType.hashCode());
		result = prime * result + (showImplicitArguments ? 1231 : 1237);
		result = prime * result + (showImplicitConversionsAndClasses ? 1231 : 1237);
		result = prime * result + (enableStripMarginOnTypeFormatting ? 1231 : 1237);
		result = prime * result + (enableIndentOnPaste ? 1231 : 1237);
		result = prime * result + (enableSemanticHighlighting ? 1231 : 1237);
		result = prime * result + ((excludedPackages == null) ? 0 : excludedPackages.hashCode());
		result = prime * result + ((fallbackScalaVersion == null) ? 0 : fallbackScalaVersion.hashCode());
		result = prime * result + ((testUserInterface == null) ? 0 : testUserInterface.hashCode());
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
		MetalsUserConfiguration other = (MetalsUserConfiguration) obj;
		if (symbolPrefixes == null) {
			if (other.symbolPrefixes != null)
				return false;
		} else if (!symbolPrefixes.equals(other.symbolPrefixes))
			return false;
		if (bloopSbtAlreadyInstalled != other.bloopSbtAlreadyInstalled)
			return false;
		if (bloopVersion == null) {
			if (other.bloopVersion != null)
				return false;
		} else if (!bloopVersion.equals(other.bloopVersion))
			return false;
		if (bloopJvmProperties == null) {
			if (other.bloopJvmProperties != null)
				return false;
		} else if (!bloopJvmProperties.equals(other.bloopJvmProperties))
			return false;
		if (ammoniteJvmProperties == null) {
			if (other.ammoniteJvmProperties != null)
				return false;
		} else if (!ammoniteJvmProperties.equals(other.ammoniteJvmProperties))
			return false;
		if (superMethodLensesEnabled != other.superMethodLensesEnabled)
			return false;
		if (showInferredType == null) {
			if (other.showInferredType != null)
				return false;
		} else if (!showInferredType.equals(other.showInferredType))
			return false;
		if (showImplicitArguments != other.showImplicitArguments)
			return false;
		if (showImplicitConversionsAndClasses != other.showImplicitConversionsAndClasses)
			return false;
		if (enableStripMarginOnTypeFormatting != other.enableStripMarginOnTypeFormatting)
			return false;
		if (enableIndentOnPaste != other.enableIndentOnPaste)
			return false;
		if (enableSemanticHighlighting != other.enableSemanticHighlighting)
			return false;
		if (excludedPackages == null) {
			if (other.excludedPackages != null)
				return false;
		} else if (!excludedPackages.equals(other.excludedPackages))
			return false;
		if (fallbackScalaVersion == null) {
			if (other.fallbackScalaVersion != null)
				return false;
		} else if (!fallbackScalaVersion.equals(other.fallbackScalaVersion))
			return false;
		if (testUserInterface == null) {
			if (other.testUserInterface != null)
				return false;
		} else if (!testUserInterface.equals(other.testUserInterface))
			return false;
		return true;
	}
}