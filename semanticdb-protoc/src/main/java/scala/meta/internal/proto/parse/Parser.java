package scala.meta.internal.proto.parse;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import scala.meta.internal.proto.diag.ProtoError;
import scala.meta.internal.proto.diag.SourceFile;
import scala.meta.internal.proto.tree.Proto;
import scala.meta.internal.proto.tree.Proto.*;

/**
 * Recursive descent parser for protobuf files.
 *
 * <p>Grammar follows the proto3 language specification.
 */
public final class Parser {

  private final Tokenizer tokenizer;
  private final SourceFile source;

  private Parser(SourceFile source) {
    this.source = source;
    this.tokenizer = new Tokenizer(source);
    tokenizer.next(); // Prime the tokenizer
  }

  /** Parse a protobuf source file. */
  public static ProtoFile parse(String source) {
    return parse(new SourceFile("<input>", source));
  }

  /** Parse a protobuf source file. */
  public static ProtoFile parse(SourceFile source) {
    Parser parser = new Parser(source);
    return parser.parseFile();
  }

  private ProtoFile parseFile() {
    int startPos = currentPos();
    Optional<SyntaxDecl> syntax = Optional.empty();
    Optional<PackageDecl> pkg = Optional.empty();
    List<ImportDecl> imports = new ArrayList<>();
    List<OptionDecl> options = new ArrayList<>();
    List<Proto> declarations = new ArrayList<>();

    while (!isAtEnd()) {
      // Skip optional empty statements (semicolons)
      if (isSymbol(';')) {
        advance();
        continue;
      }

      String ident = currentIdentifier();
      if (ident == null) {
        error("Expected declaration");
        break;
      }

      switch (ident) {
        case "syntax":
          if (syntax.isPresent()) {
            error("Duplicate syntax declaration");
          }
          syntax = Optional.of(parseSyntax());
          break;
        case "package":
          if (pkg.isPresent()) {
            error("Duplicate package declaration");
          }
          pkg = Optional.of(parsePackage());
          break;
        case "import":
          imports.add(parseImport());
          break;
        case "option":
          options.add(parseOption());
          break;
        case "message":
          declarations.add(parseMessage());
          break;
        case "enum":
          declarations.add(parseEnum());
          break;
        case "service":
          declarations.add(parseService());
          break;
        case "extend":
          declarations.add(parseExtend());
          break;
        default:
          error("Unexpected token: " + ident);
          advance();
      }
    }

    return new ProtoFile(
        startPos,
        currentPos(),
        syntax,
        pkg,
        ImmutableList.copyOf(imports),
        ImmutableList.copyOf(options),
        ImmutableList.copyOf(declarations));
  }

  private SyntaxDecl parseSyntax() {
    int startPos = currentPos();
    expectIdentifier("syntax");
    expectSymbol('=');
    String version = expectString();
    expectSymbol(';');
    return new SyntaxDecl(startPos, currentPos(), version);
  }

  private PackageDecl parsePackage() {
    int startPos = currentPos();
    expectIdentifier("package");
    ImmutableList<Ident> parts = parseQualifiedIdentifier();
    expectSymbol(';');
    return new PackageDecl(startPos, currentPos(), parts);
  }

  private ImportDecl parseImport() {
    int startPos = currentPos();
    expectIdentifier("import");

    boolean isPublic = false;
    boolean isWeak = false;

    String modifier = currentIdentifier();
    if ("public".equals(modifier)) {
      isPublic = true;
      advance();
    } else if ("weak".equals(modifier)) {
      isWeak = true;
      advance();
    }

    String path = expectString();
    expectSymbol(';');
    return new ImportDecl(startPos, currentPos(), path, isPublic, isWeak);
  }

  private OptionDecl parseOption() {
    int startPos = currentPos();
    expectIdentifier("option");
    ImmutableList<Ident> name = parseOptionName();
    expectSymbol('=');
    Proto value = parseOptionValueWithStringConcat();
    expectSymbol(';');
    return new OptionDecl(startPos, currentPos(), name, value);
  }

  /**
   * Parse option value with support for string concatenation. Multiple adjacent string literals are
   * concatenated (e.g., "foo" "bar" becomes "foobar").
   */
  private Proto parseOptionValueWithStringConcat() {
    Proto value = parseOptionValue();

    // Handle string concatenation: "string1" "string2" ...
    Token token = tokenizer.current();
    if (value instanceof Ident && token.type() == Token.Type.TYPE_STRING) {
      StringBuilder sb = new StringBuilder(((Ident) value).value());
      int endPos = value.endPosition();
      while (token.type() == Token.Type.TYPE_STRING) {
        String text = token.text();
        // Strip quotes and append
        sb.append(text.substring(1, text.length() - 1));
        endPos = token.endOffset();
        advance();
        token = tokenizer.current();
      }
      return new Ident(value.position(), endPos, sb.toString());
    }

    return value;
  }

  private ImmutableList<Ident> parseOptionName() {
    List<Ident> parts = new ArrayList<>();

    // Handle parenthesized extension names: (foo.bar).baz or (.foo.bar)
    if (isSymbol('(')) {
      advance();
      // Handle leading dot for root-level options like (.graphql)
      if (isSymbol('.')) {
        int startPos = currentPos();
        advance();
        // Add a special marker for root-level reference
        parts.add(new Ident(startPos, startPos + 1, "."));
      }
      parts.addAll(parseQualifiedIdentifier());
      expectSymbol(')');
    } else {
      parts.add(parseIdent());
    }

    while (isSymbol('.')) {
      advance();
      if (isSymbol('(')) {
        advance();
        // Handle leading dot for root-level options
        if (isSymbol('.')) {
          int startPos = currentPos();
          advance();
          parts.add(new Ident(startPos, startPos + 1, "."));
        }
        parts.addAll(parseQualifiedIdentifier());
        expectSymbol(')');
      } else {
        parts.add(parseIdent());
      }
    }

    return ImmutableList.copyOf(parts);
  }

  private Proto parseOptionValue() {
    Token token = tokenizer.current();
    int startPos = currentPos();

    if (token.type() == Token.Type.TYPE_STRING) {
      String value = token.text();
      advance();
      return new Ident(startPos, currentPos(), value);
    } else if (token.type() == Token.Type.TYPE_INTEGER) {
      String value = token.text();
      advance();
      return new Ident(startPos, currentPos(), value);
    } else if (token.type() == Token.Type.TYPE_FLOAT) {
      String value = token.text();
      advance();
      return new Ident(startPos, currentPos(), value);
    } else if (token.type() == Token.Type.TYPE_IDENTIFIER) {
      String value = token.text();
      advance();
      return new Ident(startPos, currentPos(), value);
    } else if (isSymbol('-')) {
      // Handle negative numbers: -inf, -nan, -123, etc.
      advance();
      token = tokenizer.current();
      String value;
      if (token.type() == Token.Type.TYPE_INTEGER || token.type() == Token.Type.TYPE_FLOAT) {
        value = "-" + token.text();
        advance();
      } else if (token.type() == Token.Type.TYPE_IDENTIFIER) {
        // Handle -inf, -nan
        value = "-" + token.text();
        advance();
      } else {
        error("Expected number or identifier after '-'");
        return new Ident(startPos, currentPos(), "");
      }
      return new Ident(startPos, currentPos(), value);
    } else if (isSymbol('{')) {
      // Aggregate value - skip it for now
      advance();
      int braceCount = 1;
      while (braceCount > 0 && !isAtEnd()) {
        if (isSymbol('{')) braceCount++;
        else if (isSymbol('}')) braceCount--;
        advance();
      }
      return new Ident(startPos, currentPos(), "{}");
    } else {
      error("Expected option value");
      return new Ident(startPos, currentPos(), "");
    }
  }

  private MessageDecl parseMessage() {
    int startPos = currentPos();
    expectIdentifier("message");
    Ident name = parseIdent();
    expectSymbol('{');

    List<FieldDecl> fields = new ArrayList<>();
    List<MessageDecl> nestedMessages = new ArrayList<>();
    List<EnumDecl> nestedEnums = new ArrayList<>();
    List<OneofDecl> oneofs = new ArrayList<>();
    List<MapFieldDecl> mapFields = new ArrayList<>();
    List<ExtensionsDecl> extensions = new ArrayList<>();
    List<ReservedDecl> reserved = new ArrayList<>();
    List<OptionDecl> options = new ArrayList<>();

    while (!isSymbol('}') && !isAtEnd()) {
      // Skip optional empty statements (semicolons)
      if (isSymbol(';')) {
        advance();
        continue;
      }

      // Handle leading-dot fully-qualified type names (e.g., ".google.protobuf.Timestamp")
      if (isSymbol('.')) {
        fields.add(parseField());
        continue;
      }

      String ident = currentIdentifier();
      if (ident == null) {
        error("Expected message element");
        advance();
        continue;
      }

      switch (ident) {
        case "message":
          nestedMessages.add(parseMessage());
          break;
        case "enum":
          nestedEnums.add(parseEnum());
          break;
        case "oneof":
          oneofs.add(parseOneof());
          break;
        case "map":
          mapFields.add(parseMapField());
          break;
        case "extensions":
          extensions.add(parseExtensions());
          break;
        case "reserved":
          reserved.add(parseReserved());
          break;
        case "option":
          options.add(parseOption());
          break;
        case "extend":
          // Nested extend declarations inside messages (proto2 feature)
          parseExtend();
          break;
        case "optional":
        case "required":
        case "repeated":
          fields.add(parseField());
          break;
        default:
          // Could be a type name starting a field
          if (couldBeType(ident)) {
            fields.add(parseField());
          } else {
            error("Unexpected token in message: " + ident);
            advance();
          }
      }
    }

    expectSymbol('}');
    return new MessageDecl(
        startPos,
        currentPos(),
        name,
        ImmutableList.copyOf(fields),
        ImmutableList.copyOf(nestedMessages),
        ImmutableList.copyOf(nestedEnums),
        ImmutableList.copyOf(oneofs),
        ImmutableList.copyOf(mapFields),
        ImmutableList.copyOf(extensions),
        ImmutableList.copyOf(reserved),
        ImmutableList.copyOf(options));
  }

  private FieldDecl parseField() {
    int startPos = currentPos();
    FieldModifier modifier = FieldModifier.NONE;

    String ident = currentIdentifier();
    if ("optional".equals(ident)) {
      modifier = FieldModifier.OPTIONAL;
      advance();
    } else if ("required".equals(ident)) {
      modifier = FieldModifier.REQUIRED;
      advance();
    } else if ("repeated".equals(ident)) {
      modifier = FieldModifier.REPEATED;
      advance();
    }

    // Check for group declaration (deprecated proto2 feature)
    // Syntax: optional group GroupName = 1 { ... }
    ident = currentIdentifier();
    if ("group".equals(ident)) {
      return parseGroupField(startPos, modifier);
    }

    TypeRef type = parseTypeRef();
    Ident name = parseIdent();
    expectSymbol('=');
    int number = expectInteger();
    ImmutableList<OptionDecl> options = parseFieldOptions();
    expectSymbol(';');

    return new FieldDecl(startPos, currentPos(), modifier, type, name, number, options);
  }

  /**
   * Parse a group field declaration (deprecated proto2 feature). Syntax: optional group GroupName =
   * 1 { field declarations... }
   */
  private FieldDecl parseGroupField(int startPos, FieldModifier modifier) {
    expectIdentifier("group");
    Ident name = parseIdent();
    expectSymbol('=');
    int number = expectInteger();

    // Parse the group body (similar to message body, but we skip it for simplicity)
    expectSymbol('{');
    int braceCount = 1;
    while (braceCount > 0 && !isAtEnd()) {
      if (isSymbol('{')) braceCount++;
      else if (isSymbol('}')) braceCount--;
      advance();
    }

    // Create a field with the group name as both type and field name
    TypeRef type = new TypeRef(name.position(), name.endPosition(), ImmutableList.of(name));
    // Group field names are conventionally lowercase version of the type name
    Ident fieldName = new Ident(name.position(), name.endPosition(), name.value().toLowerCase());
    return new FieldDecl(
        startPos, currentPos(), modifier, type, fieldName, number, ImmutableList.of());
  }

  private TypeRef parseTypeRef() {
    int startPos = currentPos();
    // Protobuf allows leading-dot fully-qualified type names, e.g. ".google.protobuf.Timestamp".
    // We treat the leading '.' as purely syntactic and exclude it from the TypeRef parts.
    if (isSymbol('.')) {
      advance();
    }
    ImmutableList<Ident> parts = parseQualifiedIdentifier();
    return new TypeRef(startPos, currentPos(), parts);
  }

  private ImmutableList<Ident> parseQualifiedIdentifier() {
    List<Ident> parts = new ArrayList<>();
    parts.add(parseIdent());
    while (isSymbol('.')) {
      advance();
      parts.add(parseIdent());
    }
    return ImmutableList.copyOf(parts);
  }

  private ImmutableList<OptionDecl> parseFieldOptions() {
    if (!isSymbol('[')) {
      return ImmutableList.of();
    }
    advance();
    List<OptionDecl> options = new ArrayList<>();

    while (!isSymbol(']') && !isAtEnd()) {
      int startPos = currentPos();
      ImmutableList<Ident> name = parseOptionName();
      expectSymbol('=');
      Proto value = parseOptionValueWithStringConcat();
      options.add(new OptionDecl(startPos, currentPos(), name, value));

      if (isSymbol(',')) {
        advance();
      }
    }

    expectSymbol(']');
    return ImmutableList.copyOf(options);
  }

  private EnumDecl parseEnum() {
    int startPos = currentPos();
    expectIdentifier("enum");
    Ident name = parseIdent();
    expectSymbol('{');

    List<EnumValueDecl> values = new ArrayList<>();
    List<OptionDecl> options = new ArrayList<>();
    List<ReservedDecl> reserved = new ArrayList<>();

    while (!isSymbol('}') && !isAtEnd()) {
      // Skip optional empty statements (semicolons)
      if (isSymbol(';')) {
        advance();
        continue;
      }

      String ident = currentIdentifier();
      if (ident == null) {
        error("Expected enum element");
        advance();
        continue;
      }

      if ("option".equals(ident)) {
        options.add(parseOption());
      } else if ("reserved".equals(ident)) {
        reserved.add(parseReserved());
      } else {
        values.add(parseEnumValue());
      }
    }

    expectSymbol('}');
    return new EnumDecl(
        startPos,
        currentPos(),
        name,
        ImmutableList.copyOf(values),
        ImmutableList.copyOf(options),
        ImmutableList.copyOf(reserved));
  }

  private EnumValueDecl parseEnumValue() {
    int startPos = currentPos();
    Ident name = parseIdent();
    expectSymbol('=');
    int number = expectSignedInteger();
    ImmutableList<OptionDecl> options = parseFieldOptions();
    expectSymbol(';');
    return new EnumValueDecl(startPos, currentPos(), name, number, options);
  }

  private ServiceDecl parseService() {
    int startPos = currentPos();
    expectIdentifier("service");
    Ident name = parseIdent();
    expectSymbol('{');

    List<RpcDecl> rpcs = new ArrayList<>();
    List<OptionDecl> options = new ArrayList<>();

    while (!isSymbol('}') && !isAtEnd()) {
      // Skip optional empty statements (semicolons)
      if (isSymbol(';')) {
        advance();
        continue;
      }

      String ident = currentIdentifier();
      if (ident == null) {
        error("Expected service element");
        advance();
        continue;
      }

      if ("rpc".equals(ident)) {
        rpcs.add(parseRpc());
      } else if ("option".equals(ident)) {
        options.add(parseOption());
      } else {
        error("Unexpected token in service: " + ident);
        advance();
      }
    }

    expectSymbol('}');
    return new ServiceDecl(
        startPos, currentPos(), name, ImmutableList.copyOf(rpcs), ImmutableList.copyOf(options));
  }

  private RpcDecl parseRpc() {
    int startPos = currentPos();
    expectIdentifier("rpc");
    Ident name = parseIdent();
    expectSymbol('(');

    boolean clientStreaming = false;
    if ("stream".equals(currentIdentifier())) {
      clientStreaming = true;
      advance();
    }
    TypeRef inputType = parseTypeRef();
    expectSymbol(')');

    expectIdentifier("returns");
    expectSymbol('(');

    boolean serverStreaming = false;
    if ("stream".equals(currentIdentifier())) {
      serverStreaming = true;
      advance();
    }
    TypeRef outputType = parseTypeRef();
    expectSymbol(')');

    List<OptionDecl> options = new ArrayList<>();
    if (isSymbol('{')) {
      advance();
      while (!isSymbol('}') && !isAtEnd()) {
        if ("option".equals(currentIdentifier())) {
          options.add(parseOption());
        } else {
          error("Expected option in rpc body");
          advance();
        }
      }
      expectSymbol('}');
    } else {
      expectSymbol(';');
    }

    return new RpcDecl(
        startPos,
        currentPos(),
        name,
        inputType,
        clientStreaming,
        outputType,
        serverStreaming,
        ImmutableList.copyOf(options));
  }

  private OneofDecl parseOneof() {
    int startPos = currentPos();
    expectIdentifier("oneof");
    Ident name = parseIdent();
    expectSymbol('{');

    List<FieldDecl> fields = new ArrayList<>();
    List<OptionDecl> options = new ArrayList<>();

    while (!isSymbol('}') && !isAtEnd()) {
      // Skip optional empty statements (semicolons)
      if (isSymbol(';')) {
        advance();
        continue;
      }

      // Handle leading-dot fully-qualified type names (e.g., ".google.protobuf.Timestamp")
      if (isSymbol('.')) {
        fields.add(parseOneofField());
        continue;
      }

      String ident = currentIdentifier();
      if ("option".equals(ident)) {
        options.add(parseOption());
      } else if (ident != null) {
        fields.add(parseOneofField());
      } else {
        error("Expected oneof element");
        advance();
      }
    }

    expectSymbol('}');
    return new OneofDecl(
        startPos, currentPos(), name, ImmutableList.copyOf(fields), ImmutableList.copyOf(options));
  }

  private FieldDecl parseOneofField() {
    int startPos = currentPos();
    TypeRef type = parseTypeRef();
    Ident name = parseIdent();
    expectSymbol('=');
    int number = expectInteger();
    ImmutableList<OptionDecl> options = parseFieldOptions();
    expectSymbol(';');
    return new FieldDecl(startPos, currentPos(), FieldModifier.NONE, type, name, number, options);
  }

  private MapFieldDecl parseMapField() {
    int startPos = currentPos();
    expectIdentifier("map");
    expectSymbol('<');
    TypeRef keyType = parseTypeRef();
    expectSymbol(',');
    TypeRef valueType = parseTypeRef();
    expectSymbol('>');
    Ident name = parseIdent();
    expectSymbol('=');
    int number = expectInteger();
    ImmutableList<OptionDecl> options = parseFieldOptions();
    expectSymbol(';');
    return new MapFieldDecl(startPos, currentPos(), keyType, valueType, name, number, options);
  }

  private ExtendDecl parseExtend() {
    int startPos = currentPos();
    expectIdentifier("extend");
    TypeRef extendee = parseTypeRef();
    expectSymbol('{');

    List<FieldDecl> fields = new ArrayList<>();
    while (!isSymbol('}') && !isAtEnd()) {
      fields.add(parseField());
    }

    expectSymbol('}');
    return new ExtendDecl(startPos, currentPos(), extendee, ImmutableList.copyOf(fields));
  }

  private ExtensionsDecl parseExtensions() {
    int startPos = currentPos();
    expectIdentifier("extensions");
    ImmutableList<Proto.Range> ranges = parseRanges();
    expectSymbol(';');
    return new ExtensionsDecl(startPos, currentPos(), ranges);
  }

  private ReservedDecl parseReserved() {
    int startPos = currentPos();
    expectIdentifier("reserved");

    List<Proto.Range> ranges = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();

    Token token = tokenizer.current();
    if (token.type() == Token.Type.TYPE_STRING) {
      // Reserved field names
      while (!isSymbol(';') && !isAtEnd()) {
        fieldNames.add(expectString());
        if (isSymbol(',')) {
          advance();
        }
      }
    } else {
      // Reserved field numbers
      ranges.addAll(parseRanges());
    }

    expectSymbol(';');
    return new ReservedDecl(
        startPos, currentPos(), ImmutableList.copyOf(ranges), ImmutableList.copyOf(fieldNames));
  }

  private ImmutableList<Proto.Range> parseRanges() {
    List<Proto.Range> ranges = new ArrayList<>();
    ranges.add(parseRange());
    while (isSymbol(',')) {
      advance();
      ranges.add(parseRange());
    }
    return ImmutableList.copyOf(ranges);
  }

  private Proto.Range parseRange() {
    int start = expectInteger();
    if ("to".equals(currentIdentifier())) {
      advance();
      if ("max".equals(currentIdentifier())) {
        advance();
        return new Proto.Range(start, -1);
      } else {
        int end = expectInteger();
        return new Proto.Range(start, end);
      }
    }
    return new Proto.Range(start, start);
  }

  private Ident parseIdent() {
    Token token = tokenizer.current();
    if (token.type() != Token.Type.TYPE_IDENTIFIER) {
      error("Expected identifier, got: " + token.text());
      return new Ident(currentPos(), currentPos(), "");
    }
    int startPos = token.startOffset();
    int endPos = token.endOffset();
    String value = token.text();
    advance();
    return new Ident(startPos, endPos, value);
  }

  // Helper methods

  private void advance() {
    tokenizer.next();
  }

  private boolean isAtEnd() {
    return tokenizer.current().type() == Token.Type.TYPE_END;
  }

  private int currentPos() {
    return tokenizer.current().startOffset();
  }

  private String currentIdentifier() {
    Token token = tokenizer.current();
    if (token.type() == Token.Type.TYPE_IDENTIFIER) {
      return token.text();
    }
    return null;
  }

  private boolean isSymbol(char c) {
    Token token = tokenizer.current();
    return token.type() == Token.Type.TYPE_SYMBOL && token.text().equals(String.valueOf(c));
  }

  private void expectIdentifier(String expected) {
    Token token = tokenizer.current();
    if (token.type() != Token.Type.TYPE_IDENTIFIER || !token.text().equals(expected)) {
      error("Expected '" + expected + "', got: " + token.text());
    }
    advance();
  }

  private void expectSymbol(char c) {
    if (!isSymbol(c)) {
      error("Expected '" + c + "', got: " + tokenizer.current().text());
    }
    advance();
  }

  private String expectString() {
    Token token = tokenizer.current();
    if (token.type() != Token.Type.TYPE_STRING) {
      error("Expected string, got: " + token.text());
      return "";
    }
    // Strip quotes
    String text = token.text();
    String value = text.substring(1, text.length() - 1);
    advance();
    return value;
  }

  private int expectInteger() {
    Token token = tokenizer.current();
    if (token.type() != Token.Type.TYPE_INTEGER) {
      error("Expected integer, got: " + token.text());
      return 0;
    }
    String text = token.text();
    long value;
    if (text.startsWith("0x") || text.startsWith("0X")) {
      value = Long.parseLong(text.substring(2), 16);
    } else if (text.startsWith("0") && text.length() > 1) {
      value = Long.parseLong(text.substring(1), 8);
    } else {
      value = Long.parseLong(text);
    }
    advance();
    return (int) value;
  }

  private int expectSignedInteger() {
    boolean negative = false;
    if (isSymbol('-')) {
      negative = true;
      advance();
    }
    // Use long to handle edge case of -2147483648 where the positive value overflows int
    Token token = tokenizer.current();
    if (token.type() != Token.Type.TYPE_INTEGER) {
      error("Expected integer, got: " + token.text());
      return 0;
    }
    String text = token.text();
    long value;
    if (text.startsWith("0x") || text.startsWith("0X")) {
      value = Long.parseLong(text.substring(2), 16);
    } else if (text.startsWith("0") && text.length() > 1) {
      value = Long.parseLong(text.substring(1), 8);
    } else {
      value = Long.parseLong(text);
    }
    advance();
    return (int) (negative ? -value : value);
  }

  private boolean couldBeType(String ident) {
    // Scalar types
    return ident.equals("double")
        || ident.equals("float")
        || ident.equals("int32")
        || ident.equals("int64")
        || ident.equals("uint32")
        || ident.equals("uint64")
        || ident.equals("sint32")
        || ident.equals("sint64")
        || ident.equals("fixed32")
        || ident.equals("fixed64")
        || ident.equals("sfixed32")
        || ident.equals("sfixed64")
        || ident.equals("bool")
        || ident.equals("string")
        || ident.equals("bytes")
        // Or any identifier (could be a message type)
        || Character.isLetter(ident.charAt(0));
  }

  private void error(String message) {
    Token token = tokenizer.current();
    throw new ProtoError(source.path(), token.line(), token.column(), message);
  }
}
