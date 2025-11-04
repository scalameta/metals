package scala.meta.pc;

import java.util.List;

public interface OutlineFiles {

  /**
   * Will this outline compilation be substitute for build server's compilation. Used if the first
   * compilation using build server is unsuccessful.
   */
  boolean isFirstCompileSubstitute();

  /** Files that should be outline compiled before calculating result. */
  List<VirtualFileParams> files();
}
