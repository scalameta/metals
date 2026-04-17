package scala.meta.internal.proto.binder.sym;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import scala.meta.internal.proto.tree.Proto.MessageDecl;

/** Symbol representing a protobuf message. */
public final class MessageSymbol extends Symbol {

  private final List<FieldSymbol> fields = new ArrayList<>();
  private final List<MessageSymbol> nestedMessages = new ArrayList<>();
  private final List<EnumSymbol> nestedEnums = new ArrayList<>();
  private final List<OneofSymbol> oneofs = new ArrayList<>();
  private final List<MapFieldSymbol> mapFields = new ArrayList<>();

  public MessageSymbol(String name, String fullName, MessageDecl node, Symbol owner) {
    super(name, fullName, node, owner);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.MESSAGE;
  }

  @Override
  public String semanticdbSymbol() {
    // Message symbols use owner's symbol + message name with # suffix
    // e.g., "foo/bar/Person#" for message foo.bar.Person
    // e.g., "foo/bar/Outer#Inner#" for nested message foo.bar.Outer.Inner
    Symbol owner = owner();
    if (owner == null) {
      // Top-level message - use fullName() which includes the package
      return fullName().replace('.', '/') + "#";
    }
    return owner.semanticdbSymbol() + name() + "#";
  }

  public void addField(FieldSymbol field) {
    fields.add(field);
  }

  public void addNestedMessage(MessageSymbol msg) {
    nestedMessages.add(msg);
  }

  public void addNestedEnum(EnumSymbol e) {
    nestedEnums.add(e);
  }

  public void addOneof(OneofSymbol oneof) {
    oneofs.add(oneof);
  }

  public void addMapField(MapFieldSymbol mapField) {
    mapFields.add(mapField);
  }

  public ImmutableList<FieldSymbol> fields() {
    return ImmutableList.copyOf(fields);
  }

  public ImmutableList<MessageSymbol> nestedMessages() {
    return ImmutableList.copyOf(nestedMessages);
  }

  public ImmutableList<EnumSymbol> nestedEnums() {
    return ImmutableList.copyOf(nestedEnums);
  }

  public ImmutableList<OneofSymbol> oneofs() {
    return ImmutableList.copyOf(oneofs);
  }

  public ImmutableList<MapFieldSymbol> mapFields() {
    return ImmutableList.copyOf(mapFields);
  }

  /** Find a nested symbol by name (field, nested message, nested enum, etc.). */
  public Symbol findMember(String name) {
    for (FieldSymbol f : fields) {
      if (f.name().equals(name)) return f;
    }
    for (MessageSymbol m : nestedMessages) {
      if (m.name().equals(name)) return m;
    }
    for (EnumSymbol e : nestedEnums) {
      if (e.name().equals(name)) return e;
    }
    for (OneofSymbol o : oneofs) {
      if (o.name().equals(name)) return o;
    }
    for (MapFieldSymbol mf : mapFields) {
      if (mf.name().equals(name)) return mf;
    }
    return null;
  }
}
