package scala.meta.pc;

/** Parameters for a presentation compiler request at a given range in a single source file. */
public interface RangeParams extends OffsetParams {

  /** The character end offset of the request. */
  int endOffset();
}
