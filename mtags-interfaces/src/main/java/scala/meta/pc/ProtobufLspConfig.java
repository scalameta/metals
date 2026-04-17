package scala.meta.pc;

/**
 * Configuration for Protobuf LSP features.
 *
 * <p>Each method controls whether a specific LSP feature is enabled for protobuf files.
 */
public interface ProtobufLspConfig {
  /** Returns true if diagnostics (errors/warnings) should be provided for protobuf files. */
  boolean diagnostics();

  /** Returns true if hover information should be provided for protobuf files. */
  boolean hover();

  /** Returns true if go-to-definition should be provided for protobuf files. */
  boolean definition();

  /** Returns true if completions should be provided for protobuf files. */
  boolean completions();

  /**
   * Returns true if semantic tokens (syntax highlighting) should be provided for protobuf files.
   */
  boolean semanticTokens();

  /** Returns true if SemanticDB generation should be enabled for protobuf files. */
  boolean semanticdb();

  /**
   * Optional package prefix prepended to generated {@code com.google.protobuf.*} references.
   *
   * <p>Example: {@code grpc_shaded.} generates references like {@code
   * grpc_shaded.com.google.protobuf.ByteString}.
   */
  default String javaPackagePrefix() {
    return "";
  }

  /** A default configuration with all features disabled. */
  public static ProtobufLspConfig DISABLED =
      new ProtobufLspConfig() {
        @Override
        public boolean diagnostics() {
          return false;
        }

        @Override
        public boolean hover() {
          return false;
        }

        @Override
        public boolean definition() {
          return false;
        }

        @Override
        public boolean completions() {
          return false;
        }

        @Override
        public boolean semanticTokens() {
          return false;
        }

        @Override
        public boolean semanticdb() {
          return false;
        }
      };

  /** A default configuration with all features enabled. */
  public static ProtobufLspConfig ENABLED =
      new ProtobufLspConfig() {
        @Override
        public boolean diagnostics() {
          return true;
        }

        @Override
        public boolean hover() {
          return true;
        }

        @Override
        public boolean definition() {
          return true;
        }

        @Override
        public boolean completions() {
          return true;
        }

        @Override
        public boolean semanticTokens() {
          return true;
        }

        @Override
        public boolean semanticdb() {
          return true;
        }
      };
}
