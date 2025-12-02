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
   * If enabled, the fallback presentation compiler will the classpath of all 3rd-party jars from
   * synced targets. For example, if one target has synced JUnit, then this jar is on the classpath
   * for all unsynced files (aka. the fallback classpath).
   */
  FALLBACK_CLASSPATH_ALL_3RD_PARTY,

  /**
   * If enabled, the fallback Scala presentation compiler will use full source path mode with all
   * known sources from the workspace symbol index, including unsynced files.
   */
  FULL_SOURCEPATH_FALLBACK_SCALA,

  /**
   * If enabled, uses more lightweight indexing for "Go to definition" by skipping up-frontindexing
   * of *-sources.jar files. Instead, definitions are resolved at query time by reading the "source"
   * debug attribute in classfiles.
   */
  CLASSPATH_DEFINITION_INDEX,

  /** If enabled, uses the MBT-based definition provider. */
  MBT_DEFINITION_PROVIDER,

  /** If enabled, uses the javac-based outline provider. */
  JAVAC_OUTLINE_PROVIDER,

  /** If enabled, shows compiler progress in the status bar. */
  COMPILE_PROGRESS,

  /** If enabled, uses the scalafmt range formatter. */
  SCALAFMT_RANGE_FORMATTER,

  /** If enabled, uses the MBT-based reference provider. */
  MBT_REFERENCE_PROVIDER,

  /** If enabled, uses the interactive semanticdb. */
  INTERACTIVE_SEMANTICDB,

  /** If enabled, runs the RefChecks phase in the presentation compiler. */
  RUN_PC_REFCHECKS,
}
