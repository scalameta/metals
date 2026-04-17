package scala.meta.internal.proto.binder.sym;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import scala.meta.internal.proto.tree.Proto.OneofDecl;

/** Symbol representing a protobuf oneof. */
public final class OneofSymbol extends Symbol {

  private final List<FieldSymbol> fields = new ArrayList<>();

  public OneofSymbol(String name, String fullName, OneofDecl node, Symbol owner) {
    super(name, fullName, node, owner);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.ONEOF;
  }

  @Override
  public String semanticdbSymbol() {
    // Oneof symbols use slash format with . suffix (term-like)
    // e.g., "foo/bar/Person#choice." for oneof foo.bar.Person.choice
    String ownerSym = owner().semanticdbSymbol();
    return ownerSym + name() + ".";
  }

  public void addField(FieldSymbol field) {
    fields.add(field);
  }

  public ImmutableList<FieldSymbol> fields() {
    return ImmutableList.copyOf(fields);
  }

  @Override
  public OneofDecl node() {
    return (OneofDecl) super.node();
  }
}
