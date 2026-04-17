package scala.meta.internal.proto.binder.sym;

import scala.meta.internal.proto.tree.Proto.EnumValueDecl;

/** Symbol representing a protobuf enum value. */
public final class EnumValueSymbol extends Symbol {

  private final int number;

  public EnumValueSymbol(
      String name, String fullName, EnumValueDecl node, Symbol owner, int number) {
    super(name, fullName, node, owner);
    this.number = number;
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.ENUM_VALUE;
  }

  @Override
  public String semanticdbSymbol() {
    // Enum value symbols use slash format with . suffix (term)
    // e.g., "foo/bar/Status#OK." for enum value foo.bar.Status.OK
    String ownerSym = owner().semanticdbSymbol();
    return ownerSym + name() + ".";
  }

  public int number() {
    return number;
  }

  @Override
  public EnumValueDecl node() {
    return (EnumValueDecl) super.node();
  }
}
