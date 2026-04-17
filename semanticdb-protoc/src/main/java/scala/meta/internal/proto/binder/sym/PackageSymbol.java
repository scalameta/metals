package scala.meta.internal.proto.binder.sym;

import scala.meta.internal.proto.tree.Proto;

/** Symbol representing a protobuf package. */
public final class PackageSymbol extends Symbol {

  public PackageSymbol(String name, Proto node) {
    super(name, name, node, null);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.PACKAGE;
  }

  @Override
  public String semanticdbSymbol() {
    // Package symbols use slash-separated format ending with slash
    // e.g., "foo/bar/" for package foo.bar
    return fullName().replace('.', '/') + "/";
  }
}
