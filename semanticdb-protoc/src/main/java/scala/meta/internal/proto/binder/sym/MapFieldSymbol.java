package scala.meta.internal.proto.binder.sym;

import scala.meta.internal.proto.tree.Proto.MapFieldDecl;

/** Symbol representing a protobuf map field. */
public final class MapFieldSymbol extends Symbol {

  private final int fieldNumber;
  private Symbol keyTypeSymbol;
  private Symbol valueTypeSymbol;

  public MapFieldSymbol(
      String name, String fullName, MapFieldDecl node, Symbol owner, int fieldNumber) {
    super(name, fullName, node, owner);
    this.fieldNumber = fieldNumber;
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.MAP_FIELD;
  }

  @Override
  public String semanticdbSymbol() {
    // Map field symbols use slash format with . suffix (term)
    // e.g., "foo/bar/Person#metadata." for map field foo.bar.Person.metadata
    String ownerSym = owner().semanticdbSymbol();
    return ownerSym + name() + ".";
  }

  public int fieldNumber() {
    return fieldNumber;
  }

  public Symbol keyTypeSymbol() {
    return keyTypeSymbol;
  }

  public void setKeyTypeSymbol(Symbol keyTypeSymbol) {
    this.keyTypeSymbol = keyTypeSymbol;
  }

  public Symbol valueTypeSymbol() {
    return valueTypeSymbol;
  }

  public void setValueTypeSymbol(Symbol valueTypeSymbol) {
    this.valueTypeSymbol = valueTypeSymbol;
  }

  @Override
  public MapFieldDecl node() {
    return (MapFieldDecl) super.node();
  }
}
