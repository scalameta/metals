package scala.meta.pc;

import java.net.URI;
import java.util.Optional;

/** Parameters for a presentation compiler request at a given offset in a single source file. */
public interface VirtualFileParams {
  /** The name of the source file. */
  URI uri();

  /** The text contents of the source file. */
  String text();

  /** The cancelation token for this request. */
  CancelToken token();

  /** Information about files that changed since last compilation and should be outline compiled. */
  default Optional<OutlineFiles> outlineFiles() {
    return Optional.empty();
  }

  default void checkCanceled() {
    token().checkCanceled();
  }

  /**
   * Returns the 0-based line number of the line that contains the specified UTF-16 code unit index.
   *
   * <p>API Contract: This is at worst a log(N) operation so it's OK to use in a hot path.
   */
  default int offsetToLine(int offset) {
    // NOTE(olafurpg): we can technically implement it by linearly iterating through
    // the text but it would mean having functionally correct code that is very
    // slow. The client should be able to use this API knowing that it's at least an
    // log(N) operation.
    throw new UnsupportedOperationException(
        "offsetToLine must be overridden to get acceptable performance");
  }

  /**
   * Returns the UTF-16 code unit index of the first character of the provided 0-based line number.
   *
   * <p>API Contract: This should be a constant-time (aka. O(1)) operation.
   */
  default int lineToOffset(int line) {
    throw new UnsupportedOperationException(
        "lineToOffset must be overridden to get acceptable performance");
  }
}
