package scala.meta.infra;

public enum FeatureFlag {
	MBT_WORKSPACE_SYMBOL_PROVIDER,
	/**
	 * If enabled, the presentation compiler will use the transitive closure of
	 * sources to resolve names. but with pruning of late sources (method body
	 * removal).
	 */
	SCALA_SOURCEPATH_PRUNED,

	/**
	 * If enabled, uses more lightweight indexing for "Go to definition" by skipping
	 * up-frontindexing of *-sources.jar files. Instead, definitions are resolved at
	 * query time by reading the "source" debug attribute in classfiles.
	 */
	CLASSPATH_DEFINITION_INDEX,
}
