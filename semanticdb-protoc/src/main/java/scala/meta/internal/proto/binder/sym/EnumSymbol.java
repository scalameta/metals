package scala.meta.internal.proto.binder.sym;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import scala.meta.internal.proto.tree.Proto.EnumDecl;

/** Symbol representing a protobuf enum. */
public final class EnumSymbol extends Symbol {

  private final List<EnumValueSymbol> values = new ArrayList<>();

  public EnumSymbol(String name, String fullName, EnumDecl node, Symbol owner) {
    super(name, fullName, node, owner);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.ENUM;
  }

  @Override
  public String semanticdbSymbol() {
    // Enum symbols use owner's symbol + enum name with # suffix
    // e.g., "foo/bar/Status#" for enum foo.bar.Status
    // e.g., "foo/bar/Message#Status#" for nested enum foo.bar.Message.Status
    Symbol owner = owner();
    if (owner == null) {
      // Top-level enum - use fullName() which includes the package
      return fullName().replace('.', '/') + "#";
    }
    return owner.semanticdbSymbol() + name() + "#";
  }

  public void addValue(EnumValueSymbol value) {
    values.add(value);
  }

  public ImmutableList<EnumValueSymbol> values() {
    return ImmutableList.copyOf(values);
  }

  /** Find an enum value by name. */
  public EnumValueSymbol findValue(String name) {
    for (EnumValueSymbol v : values) {
      if (v.name().equals(name)) return v;
    }
    return null;
  }

  @Override
  public EnumDecl node() {
    return (EnumDecl) super.node();
  }
}
