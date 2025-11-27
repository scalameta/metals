package scala.meta.pc;

public enum SourcePathMode {
  /** Do not use the source path. */
  DISABLED,
  /** Use the source path with complete source code. */
  FULL,
  /**
   * Use the source path with pruned source code (field and method bodies removed whenever
   * possible).
   */
  PRUNED,

  /**
   * Use the full source path from the MBT-based symbol index. Used for the fallback Scala
   * presentation compiler.
   */
  MBT;

  public boolean shouldPrune() {
    return this == PRUNED || this == MBT;
  }
}
