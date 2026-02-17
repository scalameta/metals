package scala.meta.internal.proto.binder.sym;

import scala.meta.internal.proto.tree.Proto.FieldDecl;

/** Symbol representing a protobuf field. */
public final class FieldSymbol extends Symbol {

  private final int fieldNumber;
  private Symbol typeSymbol; // Resolved type symbol (for message/enum types)

  public FieldSymbol(String name, String fullName, FieldDecl node, Symbol owner, int fieldNumber) {
    super(name, fullName, node, owner);
    this.fieldNumber = fieldNumber;
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.FIELD;
  }

  @Override
  public String semanticdbSymbol() {
    // Field symbols use owner's symbol + field name with . suffix
    // e.g., "foo/bar/Person#name." for field foo.bar.Person.name
    String ownerSym = owner().semanticdbSymbol();
    return ownerSym + name() + ".";
  }

  public int fieldNumber() {
    return fieldNumber;
  }

  public Symbol typeSymbol() {
    return typeSymbol;
  }

  public void setTypeSymbol(Symbol typeSymbol) {
    this.typeSymbol = typeSymbol;
  }

  @Override
  public FieldDecl node() {
    return (FieldDecl) super.node();
  }
}
