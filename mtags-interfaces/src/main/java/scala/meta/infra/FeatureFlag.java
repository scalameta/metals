package scala.meta.infra;

public enum FeatureFlag {
	MBT_WORKSPACE_SYMBOL_PROVIDER,
	/**
	 * If enabled, the presentation compiler will use the transitive closure of
	 * sources to resolve names. but with pruning of late sources (method body
	 * removal).
	 */
	SCALA_SOURCEPATH_PRUNED,
}
