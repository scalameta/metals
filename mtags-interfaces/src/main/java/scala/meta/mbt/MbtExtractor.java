package scala.meta.mbt;

import java.nio.file.Path;

/**
 * Interface for extracting structural information from build tools.
 *
 * <p>Implementations of this interface are loaded via ServiceLoader and downloaded on-demand using
 * Coursier, similar to how mtags is handled.
 *
 * <p>This API should remain binary compatible.
 */
public interface MbtExtractor {

  /**
   * Extract project information from a build with default settings.
   *
   * @param projectDir The root directory of the project.
   * @param outputFile The file to write the project report to.
   * @param jdkHome The Java distribution which may be used to run the build tool.
   */
  abstract void extract(Path projectDir, Path outputFile, Path jdkHome);
}
