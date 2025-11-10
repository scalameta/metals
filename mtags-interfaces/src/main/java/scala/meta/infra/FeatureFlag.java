package scala.meta.infra;

public enum FeatureFlag {
  /**
   * If enabled, uses a new repo-wide symbol index that 1) loads faster, 2) is reactive to file
   * changes, and 3) supports build-target aware queries.
   */
  MBT_V2_SYMBOL_INDEX,
  /**
   * At 100% rollout already, will get retired soon. When enabled, replaced the old BSP-based
   * workspace symbol provider. Instead of waiting to index a BSP build, it indexes the entire repo
   * instead.
   */
  MBT_WORKSPACE_SYMBOL_PROVIDER,
  /**
   * If enabled, the presentation compiler will use the transitive closure of sources to resolve
   * names. but with pruning of late sources (method body removal).
   */
  SCALA_SOURCEPATH_PRUNED,

  /**
   * If enabled, uses more lightweight indexing for "Go to definition" by skipping up-frontindexing
   * of *-sources.jar files. Instead, definitions are resolved at query time by reading the "source"
   * debug attribute in classfiles.
   */
  CLASSPATH_DEFINITION_INDEX,

  /** If enabled, uses the javac-based outline provider. */
  JAVAC_OUTLINE_PROVIDER,
}
