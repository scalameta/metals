package scala.meta.internal.proto.codegen;

import java.util.List;
import scala.meta.internal.proto.tree.Proto.ProtoFile;

/**
 * Interface for code generators that produce outline code from proto files.
 *
 * <p>Outline code contains only type signatures and method declarations, with stub implementations.
 * This allows the code to compile but not run.
 */
public interface CodeGenerator {

  /**
   * Generate outline code from a proto file.
   *
   * @param file the parsed proto file
   * @return list of generated output files
   */
  List<OutputFile> generate(ProtoFile file);

  /** Represents a generated output file. */
  class OutputFile {
    private final String path;
    private final String content;
    private final List<String> topLevelTypes;

    public OutputFile(String path, String content, List<String> topLevelTypes) {
      this.path = path;
      this.content = content;
      this.topLevelTypes = topLevelTypes;
    }

    /** The relative path of the output file. */
    public String path() {
      return path;
    }

    /** The content of the output file. */
    public String content() {
      return content;
    }

    /**
     * The simple names of the top-level types this file declares, starting with the one it is named
     * after. Simple as in unqualified - {@code User}, not {@code com.example.User} - since the
     * package is already part of {@link #path()}. A message file also declares its {@code
     * OrBuilder} interface, which callers can't recover from the path.
     */
    public List<String> topLevelTypes() {
      return topLevelTypes;
    }
  }
}
