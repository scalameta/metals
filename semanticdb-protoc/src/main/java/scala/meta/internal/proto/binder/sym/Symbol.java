package scala.meta.internal.proto.binder.sym;

import java.nio.file.Path;
import scala.meta.internal.proto.tree.Proto;

/**
 * Base class for protobuf symbols.
 *
 * <p>Symbols represent named entities in protobuf files: messages, fields, enums, etc. Each symbol
 * has a unique identifier based on its location in the proto file.
 */
public abstract class Symbol {

  private final String name;
  private final String fullName;
  private final Proto node;
  private final Symbol owner;
  private Path sourcePath;
  private String sourceText;

  protected Symbol(String name, String fullName, Proto node, Symbol owner) {
    this.name = name;
    this.fullName = fullName;
    this.node = node;
    this.owner = owner;
  }

  /** The simple name of this symbol. */
  public String name() {
    return name;
  }

  /** The fully qualified name of this symbol, e.g., "foo.bar.Person". */
  public String fullName() {
    return fullName;
  }

  /** The AST node that defines this symbol. */
  public Proto node() {
    return node;
  }

  /** The symbol that contains this symbol, or null for package-level symbols. */
  public Symbol owner() {
    return owner;
  }

  /** The source file path where this symbol is defined. */
  public Path sourcePath() {
    return sourcePath;
  }

  /** Set the source file path. */
  public void setSourcePath(Path sourcePath) {
    this.sourcePath = sourcePath;
  }

  /** The source text of the file where this symbol is defined. */
  public String sourceText() {
    return sourceText;
  }

  /** Set the source text. */
  public void setSourceText(String sourceText) {
    this.sourceText = sourceText;
  }

  /** The kind of this symbol. */
  public abstract SymbolKind kind();

  /** Convert to SemanticDB symbol format. */
  public abstract String semanticdbSymbol();

  public enum SymbolKind {
    PACKAGE,
    MESSAGE,
    FIELD,
    ENUM,
    ENUM_VALUE,
    SERVICE,
    RPC,
    ONEOF,
    MAP_FIELD
  }

  @Override
  public String toString() {
    return kind() + " " + fullName;
  }
}
