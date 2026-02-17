package scala.meta.internal.proto.tree;

import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Protobuf AST nodes.
 *
 * <p>All nodes are immutable and track their source position.
 */
public abstract class Proto {

  private final int position;
  private final int endPosition;

  protected Proto(int position, int endPosition) {
    this.position = position;
    this.endPosition = endPosition;
  }

  public int position() {
    return position;
  }

  public int endPosition() {
    return endPosition;
  }

  public abstract Kind kind();

  public abstract <R, P> R accept(Visitor<R, P> visitor, P param);

  public enum Kind {
    PROTO_FILE,
    SYNTAX_DECL,
    PACKAGE_DECL,
    IMPORT_DECL,
    OPTION_DECL,
    MESSAGE_DECL,
    FIELD_DECL,
    ENUM_DECL,
    ENUM_VALUE_DECL,
    SERVICE_DECL,
    RPC_DECL,
    ONEOF_DECL,
    MAP_FIELD_DECL,
    EXTEND_DECL,
    EXTENSIONS_DECL,
    RESERVED_DECL,
    OPTION_VALUE,
    TYPE_REF,
    IDENT
  }

  public interface Visitor<R, P> {
    R visitProtoFile(ProtoFile node, P param);

    R visitSyntaxDecl(SyntaxDecl node, P param);

    R visitPackageDecl(PackageDecl node, P param);

    R visitImportDecl(ImportDecl node, P param);

    R visitOptionDecl(OptionDecl node, P param);

    R visitMessageDecl(MessageDecl node, P param);

    R visitFieldDecl(FieldDecl node, P param);

    R visitEnumDecl(EnumDecl node, P param);

    R visitEnumValueDecl(EnumValueDecl node, P param);

    R visitServiceDecl(ServiceDecl node, P param);

    R visitRpcDecl(RpcDecl node, P param);

    R visitOneofDecl(OneofDecl node, P param);

    R visitMapFieldDecl(MapFieldDecl node, P param);

    R visitExtendDecl(ExtendDecl node, P param);

    R visitExtensionsDecl(ExtensionsDecl node, P param);

    R visitReservedDecl(ReservedDecl node, P param);

    R visitTypeRef(TypeRef node, P param);

    R visitIdent(Ident node, P param);
  }

  /** A complete protobuf file. */
  public static final class ProtoFile extends Proto {
    private final Optional<SyntaxDecl> syntax;
    private final Optional<PackageDecl> pkg;
    private final ImmutableList<ImportDecl> imports;
    private final ImmutableList<OptionDecl> options;
    private final ImmutableList<Proto> declarations; // messages, enums, services, extends

    public ProtoFile(
        int position,
        int endPosition,
        Optional<SyntaxDecl> syntax,
        Optional<PackageDecl> pkg,
        ImmutableList<ImportDecl> imports,
        ImmutableList<OptionDecl> options,
        ImmutableList<Proto> declarations) {
      super(position, endPosition);
      this.syntax = syntax;
      this.pkg = pkg;
      this.imports = imports;
      this.options = options;
      this.declarations = declarations;
    }

    public Optional<SyntaxDecl> syntax() {
      return syntax;
    }

    public Optional<PackageDecl> pkg() {
      return pkg;
    }

    public ImmutableList<ImportDecl> imports() {
      return imports;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    public ImmutableList<Proto> declarations() {
      return declarations;
    }

    @Override
    public Kind kind() {
      return Kind.PROTO_FILE;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitProtoFile(this, param);
    }
  }

  /** syntax = "proto3"; */
  public static final class SyntaxDecl extends Proto {
    private final String version; // "proto2" or "proto3"

    public SyntaxDecl(int position, int endPosition, String version) {
      super(position, endPosition);
      this.version = version;
    }

    public String version() {
      return version;
    }

    @Override
    public Kind kind() {
      return Kind.SYNTAX_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitSyntaxDecl(this, param);
    }
  }

  /** package foo.bar; */
  public static final class PackageDecl extends Proto {
    private final ImmutableList<Ident> parts;

    public PackageDecl(int position, int endPosition, ImmutableList<Ident> parts) {
      super(position, endPosition);
      this.parts = parts;
    }

    public ImmutableList<Ident> parts() {
      return parts;
    }

    public String fullName() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.size(); i++) {
        if (i > 0) sb.append(".");
        sb.append(parts.get(i).value());
      }
      return sb.toString();
    }

    @Override
    public Kind kind() {
      return Kind.PACKAGE_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitPackageDecl(this, param);
    }
  }

  /** import "path/to/file.proto"; */
  public static final class ImportDecl extends Proto {
    private final String path;
    private final boolean isPublic;
    private final boolean isWeak;

    public ImportDecl(
        int position, int endPosition, String path, boolean isPublic, boolean isWeak) {
      super(position, endPosition);
      this.path = path;
      this.isPublic = isPublic;
      this.isWeak = isWeak;
    }

    public String path() {
      return path;
    }

    public boolean isPublic() {
      return isPublic;
    }

    public boolean isWeak() {
      return isWeak;
    }

    @Override
    public Kind kind() {
      return Kind.IMPORT_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitImportDecl(this, param);
    }
  }

  /** option java_package = "..."; */
  public static final class OptionDecl extends Proto {
    private final ImmutableList<Ident> name;
    private final Proto value;

    public OptionDecl(int position, int endPosition, ImmutableList<Ident> name, Proto value) {
      super(position, endPosition);
      this.name = name;
      this.value = value;
    }

    public ImmutableList<Ident> name() {
      return name;
    }

    public Proto value() {
      return value;
    }

    @Override
    public Kind kind() {
      return Kind.OPTION_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitOptionDecl(this, param);
    }
  }

  /** message Foo { ... } */
  public static final class MessageDecl extends Proto {
    private final Ident name;
    private final ImmutableList<FieldDecl> fields;
    private final ImmutableList<MessageDecl> nestedMessages;
    private final ImmutableList<EnumDecl> nestedEnums;
    private final ImmutableList<OneofDecl> oneofs;
    private final ImmutableList<MapFieldDecl> mapFields;
    private final ImmutableList<ExtensionsDecl> extensions;
    private final ImmutableList<ReservedDecl> reserved;
    private final ImmutableList<OptionDecl> options;

    public MessageDecl(
        int position,
        int endPosition,
        Ident name,
        ImmutableList<FieldDecl> fields,
        ImmutableList<MessageDecl> nestedMessages,
        ImmutableList<EnumDecl> nestedEnums,
        ImmutableList<OneofDecl> oneofs,
        ImmutableList<MapFieldDecl> mapFields,
        ImmutableList<ExtensionsDecl> extensions,
        ImmutableList<ReservedDecl> reserved,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.name = name;
      this.fields = fields;
      this.nestedMessages = nestedMessages;
      this.nestedEnums = nestedEnums;
      this.oneofs = oneofs;
      this.mapFields = mapFields;
      this.extensions = extensions;
      this.reserved = reserved;
      this.options = options;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<FieldDecl> fields() {
      return fields;
    }

    public ImmutableList<MessageDecl> nestedMessages() {
      return nestedMessages;
    }

    public ImmutableList<EnumDecl> nestedEnums() {
      return nestedEnums;
    }

    public ImmutableList<OneofDecl> oneofs() {
      return oneofs;
    }

    public ImmutableList<MapFieldDecl> mapFields() {
      return mapFields;
    }

    public ImmutableList<ExtensionsDecl> extensions() {
      return extensions;
    }

    public ImmutableList<ReservedDecl> reserved() {
      return reserved;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.MESSAGE_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitMessageDecl(this, param);
    }
  }

  /** Field modifier: optional, required, repeated. */
  public enum FieldModifier {
    NONE,
    OPTIONAL,
    REQUIRED,
    REPEATED
  }

  /** string name = 1; */
  public static final class FieldDecl extends Proto {
    private final FieldModifier modifier;
    private final TypeRef type;
    private final Ident name;
    private final int number;
    private final ImmutableList<OptionDecl> options;

    public FieldDecl(
        int position,
        int endPosition,
        FieldModifier modifier,
        TypeRef type,
        Ident name,
        int number,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.modifier = modifier;
      this.type = type;
      this.name = name;
      this.number = number;
      this.options = options;
    }

    public FieldModifier modifier() {
      return modifier;
    }

    public TypeRef type() {
      return type;
    }

    public Ident name() {
      return name;
    }

    public int number() {
      return number;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.FIELD_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitFieldDecl(this, param);
    }
  }

  /** enum Status { ... } */
  public static final class EnumDecl extends Proto {
    private final Ident name;
    private final ImmutableList<EnumValueDecl> values;
    private final ImmutableList<OptionDecl> options;
    private final ImmutableList<ReservedDecl> reserved;

    public EnumDecl(
        int position,
        int endPosition,
        Ident name,
        ImmutableList<EnumValueDecl> values,
        ImmutableList<OptionDecl> options,
        ImmutableList<ReservedDecl> reserved) {
      super(position, endPosition);
      this.name = name;
      this.values = values;
      this.options = options;
      this.reserved = reserved;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<EnumValueDecl> values() {
      return values;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    public ImmutableList<ReservedDecl> reserved() {
      return reserved;
    }

    @Override
    public Kind kind() {
      return Kind.ENUM_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitEnumDecl(this, param);
    }
  }

  /** UNKNOWN = 0; */
  public static final class EnumValueDecl extends Proto {
    private final Ident name;
    private final int number;
    private final ImmutableList<OptionDecl> options;

    public EnumValueDecl(
        int position, int endPosition, Ident name, int number, ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.name = name;
      this.number = number;
      this.options = options;
    }

    public Ident name() {
      return name;
    }

    public int number() {
      return number;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.ENUM_VALUE_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitEnumValueDecl(this, param);
    }
  }

  /** service Greeter { ... } */
  public static final class ServiceDecl extends Proto {
    private final Ident name;
    private final ImmutableList<RpcDecl> rpcs;
    private final ImmutableList<OptionDecl> options;

    public ServiceDecl(
        int position,
        int endPosition,
        Ident name,
        ImmutableList<RpcDecl> rpcs,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.name = name;
      this.rpcs = rpcs;
      this.options = options;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<RpcDecl> rpcs() {
      return rpcs;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.SERVICE_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitServiceDecl(this, param);
    }
  }

  /** rpc GetUser(GetUserRequest) returns (GetUserResponse); */
  public static final class RpcDecl extends Proto {
    private final Ident name;
    private final TypeRef inputType;
    private final boolean clientStreaming;
    private final TypeRef outputType;
    private final boolean serverStreaming;
    private final ImmutableList<OptionDecl> options;

    public RpcDecl(
        int position,
        int endPosition,
        Ident name,
        TypeRef inputType,
        boolean clientStreaming,
        TypeRef outputType,
        boolean serverStreaming,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.name = name;
      this.inputType = inputType;
      this.clientStreaming = clientStreaming;
      this.outputType = outputType;
      this.serverStreaming = serverStreaming;
      this.options = options;
    }

    public Ident name() {
      return name;
    }

    public TypeRef inputType() {
      return inputType;
    }

    public boolean clientStreaming() {
      return clientStreaming;
    }

    public TypeRef outputType() {
      return outputType;
    }

    public boolean serverStreaming() {
      return serverStreaming;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.RPC_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitRpcDecl(this, param);
    }
  }

  /** oneof choice { ... } */
  public static final class OneofDecl extends Proto {
    private final Ident name;
    private final ImmutableList<FieldDecl> fields;
    private final ImmutableList<OptionDecl> options;

    public OneofDecl(
        int position,
        int endPosition,
        Ident name,
        ImmutableList<FieldDecl> fields,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.name = name;
      this.fields = fields;
      this.options = options;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<FieldDecl> fields() {
      return fields;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.ONEOF_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitOneofDecl(this, param);
    }
  }

  /** map<string, int32> m = 1; */
  public static final class MapFieldDecl extends Proto {
    private final TypeRef keyType;
    private final TypeRef valueType;
    private final Ident name;
    private final int number;
    private final ImmutableList<OptionDecl> options;

    public MapFieldDecl(
        int position,
        int endPosition,
        TypeRef keyType,
        TypeRef valueType,
        Ident name,
        int number,
        ImmutableList<OptionDecl> options) {
      super(position, endPosition);
      this.keyType = keyType;
      this.valueType = valueType;
      this.name = name;
      this.number = number;
      this.options = options;
    }

    public TypeRef keyType() {
      return keyType;
    }

    public TypeRef valueType() {
      return valueType;
    }

    public Ident name() {
      return name;
    }

    public int number() {
      return number;
    }

    public ImmutableList<OptionDecl> options() {
      return options;
    }

    @Override
    public Kind kind() {
      return Kind.MAP_FIELD_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitMapFieldDecl(this, param);
    }
  }

  /** extend Foo { ... } */
  public static final class ExtendDecl extends Proto {
    private final TypeRef extendee;
    private final ImmutableList<FieldDecl> fields;

    public ExtendDecl(
        int position, int endPosition, TypeRef extendee, ImmutableList<FieldDecl> fields) {
      super(position, endPosition);
      this.extendee = extendee;
      this.fields = fields;
    }

    public TypeRef extendee() {
      return extendee;
    }

    public ImmutableList<FieldDecl> fields() {
      return fields;
    }

    @Override
    public Kind kind() {
      return Kind.EXTEND_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitExtendDecl(this, param);
    }
  }

  /** extensions 100 to 199; */
  public static final class ExtensionsDecl extends Proto {
    private final ImmutableList<Range> ranges;

    public ExtensionsDecl(int position, int endPosition, ImmutableList<Range> ranges) {
      super(position, endPosition);
      this.ranges = ranges;
    }

    public ImmutableList<Range> ranges() {
      return ranges;
    }

    @Override
    public Kind kind() {
      return Kind.EXTENSIONS_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitExtensionsDecl(this, param);
    }
  }

  /** reserved 2, 15, 9 to 11; or reserved "foo", "bar"; */
  public static final class ReservedDecl extends Proto {
    private final ImmutableList<Range> ranges;
    private final ImmutableList<String> fieldNames;

    public ReservedDecl(
        int position,
        int endPosition,
        ImmutableList<Range> ranges,
        ImmutableList<String> fieldNames) {
      super(position, endPosition);
      this.ranges = ranges;
      this.fieldNames = fieldNames;
    }

    public ImmutableList<Range> ranges() {
      return ranges;
    }

    public ImmutableList<String> fieldNames() {
      return fieldNames;
    }

    @Override
    public Kind kind() {
      return Kind.RESERVED_DECL;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitReservedDecl(this, param);
    }
  }

  /** A range like 9 to 11 or just 9. */
  public static final class Range {
    private final int start;
    private final int end; // -1 for "max"

    public Range(int start, int end) {
      this.start = start;
      this.end = end;
    }

    public int start() {
      return start;
    }

    public int end() {
      return end;
    }

    public boolean isMax() {
      return end == -1;
    }
  }

  /** A type reference like "string", "int32", "foo.bar.Message". */
  public static final class TypeRef extends Proto {
    private final ImmutableList<Ident> parts;

    public TypeRef(int position, int endPosition, ImmutableList<Ident> parts) {
      super(position, endPosition);
      this.parts = parts;
    }

    public ImmutableList<Ident> parts() {
      return parts;
    }

    public String fullName() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.size(); i++) {
        if (i > 0) sb.append(".");
        sb.append(parts.get(i).value());
      }
      return sb.toString();
    }

    public boolean isScalar() {
      if (parts.size() != 1) return false;
      String name = parts.get(0).value();
      return name.equals("double")
          || name.equals("float")
          || name.equals("int32")
          || name.equals("int64")
          || name.equals("uint32")
          || name.equals("uint64")
          || name.equals("sint32")
          || name.equals("sint64")
          || name.equals("fixed32")
          || name.equals("fixed64")
          || name.equals("sfixed32")
          || name.equals("sfixed64")
          || name.equals("bool")
          || name.equals("string")
          || name.equals("bytes");
    }

    @Override
    public Kind kind() {
      return Kind.TYPE_REF;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitTypeRef(this, param);
    }
  }

  /** An identifier. */
  public static final class Ident extends Proto {
    private final String value;

    public Ident(int position, int endPosition, String value) {
      super(position, endPosition);
      this.value = value;
    }

    public String value() {
      return value;
    }

    @Override
    public Kind kind() {
      return Kind.IDENT;
    }

    @Override
    public <R, P> R accept(Visitor<R, P> visitor, P param) {
      return visitor.visitIdent(this, param);
    }
  }
}
