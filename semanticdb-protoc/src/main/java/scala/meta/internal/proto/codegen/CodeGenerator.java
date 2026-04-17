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

    public OutputFile(String path, String content) {
      this.path = path;
      this.content = content;
    }

    /** The relative path of the output file. */
    public String path() {
      return path;
    }

    /** The content of the output file. */
    public String content() {
      return content;
    }
  }
}
