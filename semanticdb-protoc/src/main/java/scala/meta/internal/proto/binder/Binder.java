package scala.meta.internal.proto.binder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import scala.meta.internal.proto.binder.sym.*;
import scala.meta.internal.proto.tree.Proto;
import scala.meta.internal.proto.tree.Proto.*;

/**
 * Binds AST nodes to symbols.
 *
 * <p>Phase 0: Resolve imports and add imported symbols to the symbol table. Phase 1: Create symbols
 * for all declarations and build the symbol table. Phase 2: Resolve type references to their
 * symbols.
 */
public final class Binder {

  private final ProtoFile file;
  private final SymbolTable symbolTable;
  private final List<BindingError> errors;
  private final ImportResolver importResolver;
  private final Path sourcePath;
  private final Set<String> processedImports;
  private final Set<Symbol> currentFileSymbols;

  private Binder(ProtoFile file, ImportResolver importResolver, Path sourcePath) {
    this.file = file;
    this.symbolTable = new SymbolTable();
    this.errors = new ArrayList<>();
    this.importResolver = importResolver;
    this.sourcePath = sourcePath;
    this.processedImports = new HashSet<>();
    this.currentFileSymbols = new HashSet<>();
  }

  /** Bind a proto file and return the symbol table. */
  public static SymbolTable bind(ProtoFile file) {
    return bindWithErrors(file, null, null).symbolTable();
  }

  /** Bind a proto file and return both the symbol table and any errors. */
  public static BindingResult bindWithErrors(ProtoFile file) {
    return bindWithErrors(file, null, null);
  }

  /** Bind a proto file with import resolution. */
  public static BindingResult bindWithErrors(
      ProtoFile file, ImportResolver importResolver, Path sourcePath) {
    Binder binder = new Binder(file, importResolver, sourcePath);
    binder.bindPhase0();
    binder.bindPhase1();
    binder.bindPhase2();
    return new BindingResult(binder.symbolTable, binder.errors);
  }

  /** Result of binding including both symbol table and errors. */
  public static final class BindingResult {
    private final SymbolTable symbolTable;
    private final List<BindingError> errors;

    public BindingResult(SymbolTable symbolTable, List<BindingError> errors) {
      this.symbolTable = symbolTable;
      this.errors = errors;
    }

    public SymbolTable symbolTable() {
      return symbolTable;
    }

    public List<BindingError> errors() {
      return errors;
    }
  }

  /** A binding error for an unresolved type reference. */
  public static final class BindingError {
    private final TypeRef typeRef;
    private final String message;
    private final int position;

    public BindingError(TypeRef typeRef, String message) {
      this.typeRef = typeRef;
      this.message = message;
      this.position = typeRef.position();
    }

    public TypeRef typeRef() {
      return typeRef;
    }

    public String message() {
      return message;
    }

    /** Get the byte offset position of the error. */
    public int position() {
      return position;
    }
  }

  /** Phase 0: Resolve imports and bind symbols from imported files. */
  private void bindPhase0() {
    if (importResolver == null) {
      return; // No import resolution
    }

    for (ImportDecl importDecl : file.imports()) {
      String importPath = importDecl.path();
      if (processedImports.contains(importPath)) {
        continue; // Already processed
      }
      processedImports.add(importPath);

      // Get the resolved path first so we can track source file info
      Path resolvedPath = importResolver.resolveImportPath(importDecl, sourcePath);
      if (resolvedPath == null) {
        continue; // Import not found
      }

      ProtoFile importedFile = importResolver.resolveImport(importDecl, sourcePath);
      if (importedFile == null) {
        // Import not found - could add a warning here, but for now just skip
        continue;
      }

      // Read the file content for position calculations
      String sourceText = null;
      try {
        sourceText = Files.readString(resolvedPath);
      } catch (IOException e) {
        // Ignore, sourceText will be null
      }

      // Bind symbols from imported file (without processing its imports recursively)
      bindImportedFile(importedFile, resolvedPath, sourceText);
    }
  }

  /** Bind symbols from an imported file. */
  private void bindImportedFile(ProtoFile importedFile, Path importedPath, String sourceText) {
    // Get the package name
    String packageName = "";
    if (importedFile.pkg().isPresent()) {
      PackageDecl pkg = importedFile.pkg().get();
      packageName = pkg.fullName();
    }

    // Bind all top-level declarations from the imported file
    for (Proto decl : importedFile.declarations()) {
      Symbol sym = null;
      if (decl instanceof MessageDecl) {
        sym = bindMessage((MessageDecl) decl, packageName, null);
      } else if (decl instanceof EnumDecl) {
        sym = bindEnum((EnumDecl) decl, packageName, null);
      } else if (decl instanceof ServiceDecl) {
        sym = bindService((ServiceDecl) decl, packageName, null);
      }
      // Set source file info for imported symbols
      if (sym != null) {
        setSourceInfoRecursively(sym, importedPath, sourceText);
      }
    }
  }

  /** Recursively set source path and text for a symbol and all its children. */
  private void setSourceInfoRecursively(Symbol sym, Path path, String text) {
    sym.setSourcePath(path);
    sym.setSourceText(text);

    if (sym instanceof MessageSymbol) {
      MessageSymbol msg = (MessageSymbol) sym;
      msg.fields().forEach(f -> setSourceInfoRecursively(f, path, text));
      msg.nestedMessages().forEach(m -> setSourceInfoRecursively(m, path, text));
      msg.nestedEnums().forEach(e -> setSourceInfoRecursively(e, path, text));
      msg.oneofs().forEach(o -> setSourceInfoRecursively(o, path, text));
      msg.mapFields().forEach(mf -> setSourceInfoRecursively(mf, path, text));
    } else if (sym instanceof EnumSymbol) {
      EnumSymbol e = (EnumSymbol) sym;
      e.values().forEach(v -> setSourceInfoRecursively(v, path, text));
    } else if (sym instanceof ServiceSymbol) {
      ServiceSymbol svc = (ServiceSymbol) sym;
      svc.rpcs().forEach(r -> setSourceInfoRecursively(r, path, text));
    } else if (sym instanceof OneofSymbol) {
      OneofSymbol oneof = (OneofSymbol) sym;
      oneof.fields().forEach(f -> setSourceInfoRecursively(f, path, text));
    }
  }

  /** Phase 1: Create symbols for all declarations in the current file. */
  private void bindPhase1() {
    // Handle package
    String packageName = "";
    if (file.pkg().isPresent()) {
      PackageDecl pkg = file.pkg().get();
      packageName = pkg.fullName();
      PackageSymbol pkgSym = new PackageSymbol(packageName, pkg);
      symbolTable.setPackageSymbol(pkgSym);
    }

    // Bind all top-level declarations from the CURRENT file
    for (Proto decl : file.declarations()) {
      Symbol sym = null;
      if (decl instanceof MessageDecl) {
        sym = bindMessage((MessageDecl) decl, packageName, null);
      } else if (decl instanceof EnumDecl) {
        sym = bindEnum((EnumDecl) decl, packageName, null);
      } else if (decl instanceof ServiceDecl) {
        sym = bindService((ServiceDecl) decl, packageName, null);
      }
      if (sym != null) {
        // Track that this symbol and all its children are from the current file
        addToCurrentFile(sym);
      }
    }
  }

  /** Recursively mark a symbol and all its children as being from the current file. */
  private void addToCurrentFile(Symbol sym) {
    currentFileSymbols.add(sym);
    if (sym instanceof MessageSymbol) {
      MessageSymbol msg = (MessageSymbol) sym;
      msg.fields().forEach(this::addToCurrentFile);
      msg.nestedMessages().forEach(this::addToCurrentFile);
      msg.nestedEnums().forEach(this::addToCurrentFile);
      msg.oneofs().forEach(this::addToCurrentFile);
      msg.mapFields().forEach(this::addToCurrentFile);
    } else if (sym instanceof EnumSymbol) {
      EnumSymbol e = (EnumSymbol) sym;
      e.values().forEach(this::addToCurrentFile);
    } else if (sym instanceof ServiceSymbol) {
      ServiceSymbol svc = (ServiceSymbol) sym;
      svc.rpcs().forEach(this::addToCurrentFile);
    } else if (sym instanceof OneofSymbol) {
      OneofSymbol oneof = (OneofSymbol) sym;
      oneof.fields().forEach(this::addToCurrentFile);
    }
  }

  /** Bind a message and all its contents. */
  private MessageSymbol bindMessage(MessageDecl msg, String prefix, Symbol owner) {
    String name = msg.name().value();
    String fullName = prefix.isEmpty() ? name : prefix + "." + name;

    MessageSymbol msgSym = new MessageSymbol(name, fullName, msg, owner);
    symbolTable.register(msgSym);

    // Bind fields
    for (FieldDecl field : msg.fields()) {
      bindField(field, fullName, msgSym);
    }

    // Bind nested messages
    for (MessageDecl nested : msg.nestedMessages()) {
      MessageSymbol nestedSym = bindMessage(nested, fullName, msgSym);
      msgSym.addNestedMessage(nestedSym);
    }

    // Bind nested enums
    for (EnumDecl nested : msg.nestedEnums()) {
      EnumSymbol nestedSym = bindEnum(nested, fullName, msgSym);
      msgSym.addNestedEnum(nestedSym);
    }

    // Bind oneofs
    for (OneofDecl oneof : msg.oneofs()) {
      OneofSymbol oneofSym = bindOneof(oneof, fullName, msgSym);
      msgSym.addOneof(oneofSym);
    }

    // Bind map fields
    for (MapFieldDecl mapField : msg.mapFields()) {
      MapFieldSymbol mapSym = bindMapField(mapField, fullName, msgSym);
      msgSym.addMapField(mapSym);
    }

    return msgSym;
  }

  /** Bind a field. */
  private FieldSymbol bindField(FieldDecl field, String prefix, Symbol owner) {
    String name = field.name().value();
    String fullName = prefix + "." + name;

    FieldSymbol fieldSym = new FieldSymbol(name, fullName, field, owner, field.number());
    symbolTable.register(fieldSym);

    if (owner instanceof MessageSymbol) {
      ((MessageSymbol) owner).addField(fieldSym);
    }

    return fieldSym;
  }

  /** Bind an enum and all its values. */
  private EnumSymbol bindEnum(EnumDecl e, String prefix, Symbol owner) {
    String name = e.name().value();
    String fullName = prefix.isEmpty() ? name : prefix + "." + name;

    EnumSymbol enumSym = new EnumSymbol(name, fullName, e, owner);
    symbolTable.register(enumSym);

    // Bind enum values
    for (EnumValueDecl value : e.values()) {
      EnumValueSymbol valueSym = bindEnumValue(value, fullName, enumSym);
      enumSym.addValue(valueSym);
    }

    return enumSym;
  }

  /** Bind an enum value. */
  private EnumValueSymbol bindEnumValue(EnumValueDecl value, String prefix, EnumSymbol owner) {
    String name = value.name().value();
    String fullName = prefix + "." + name;

    EnumValueSymbol valueSym = new EnumValueSymbol(name, fullName, value, owner, value.number());
    symbolTable.register(valueSym);

    return valueSym;
  }

  /** Bind a service and all its RPCs. */
  private ServiceSymbol bindService(ServiceDecl svc, String prefix, Symbol owner) {
    String name = svc.name().value();
    String fullName = prefix.isEmpty() ? name : prefix + "." + name;

    ServiceSymbol svcSym = new ServiceSymbol(name, fullName, svc, owner);
    symbolTable.register(svcSym);

    // Bind RPCs
    for (RpcDecl rpc : svc.rpcs()) {
      RpcSymbol rpcSym = bindRpc(rpc, fullName, svcSym);
      svcSym.addRpc(rpcSym);
    }

    return svcSym;
  }

  /** Bind an RPC method. */
  private RpcSymbol bindRpc(RpcDecl rpc, String prefix, ServiceSymbol owner) {
    String name = rpc.name().value();
    String fullName = prefix + "." + name;

    RpcSymbol rpcSym = new RpcSymbol(name, fullName, rpc, owner);
    symbolTable.register(rpcSym);

    return rpcSym;
  }

  /** Bind a oneof. */
  private OneofSymbol bindOneof(OneofDecl oneof, String prefix, MessageSymbol owner) {
    String name = oneof.name().value();
    String fullName = prefix + "." + name;

    OneofSymbol oneofSym = new OneofSymbol(name, fullName, oneof, owner);
    symbolTable.register(oneofSym);

    // Bind oneof fields
    for (FieldDecl field : oneof.fields()) {
      FieldSymbol fieldSym = bindField(field, prefix, owner);
      oneofSym.addField(fieldSym);
    }

    return oneofSym;
  }

  /** Bind a map field. */
  private MapFieldSymbol bindMapField(MapFieldDecl mapField, String prefix, MessageSymbol owner) {
    String name = mapField.name().value();
    String fullName = prefix + "." + name;

    MapFieldSymbol mapSym = new MapFieldSymbol(name, fullName, mapField, owner, mapField.number());
    symbolTable.register(mapSym);

    return mapSym;
  }

  /** Phase 2: Resolve type references to their symbols. Only report errors for the current file. */
  private void bindPhase2() {
    // Resolve field types - only report errors for symbols from the current file
    for (Symbol sym : symbolTable.allSymbols()) {
      boolean isFromCurrentFile = currentFileSymbols.contains(sym);

      if (sym instanceof FieldSymbol) {
        FieldSymbol field = (FieldSymbol) sym;
        TypeRef typeRef = field.node().type();
        if (!typeRef.isScalar()) {
          Symbol typeSym = symbolTable.resolveType(typeRef.fullName(), field.owner());
          if (typeSym == null && isFromCurrentFile) {
            // Only report error if this field is from the current file
            errors.add(new BindingError(typeRef, "Unknown type: " + typeRef.fullName()));
          }
          field.setTypeSymbol(typeSym);
        }
      } else if (sym instanceof MapFieldSymbol) {
        MapFieldSymbol mapField = (MapFieldSymbol) sym;
        // Key type must be scalar, value type might be a message/enum
        TypeRef valueType = mapField.node().valueType();
        if (!valueType.isScalar()) {
          Symbol typeSym = symbolTable.resolveType(valueType.fullName(), mapField.owner());
          if (typeSym == null && isFromCurrentFile) {
            // Only report error if this map field is from the current file
            errors.add(new BindingError(valueType, "Unknown type: " + valueType.fullName()));
          }
          mapField.setValueTypeSymbol(typeSym);
        }
      } else if (sym instanceof RpcSymbol) {
        RpcSymbol rpc = (RpcSymbol) sym;
        // Resolve input and output types
        TypeRef inputType = rpc.node().inputType();
        Symbol inputSym = symbolTable.resolveType(inputType.fullName(), rpc.owner());
        if (inputSym == null && isFromCurrentFile) {
          // Only report error if this RPC is from the current file
          errors.add(new BindingError(inputType, "Unknown type: " + inputType.fullName()));
        }
        rpc.setInputTypeSymbol(inputSym);

        TypeRef outputType = rpc.node().outputType();
        Symbol outputSym = symbolTable.resolveType(outputType.fullName(), rpc.owner());
        if (outputSym == null && isFromCurrentFile) {
          // Only report error if this RPC is from the current file
          errors.add(new BindingError(outputType, "Unknown type: " + outputType.fullName()));
        }
        rpc.setOutputTypeSymbol(outputSym);
      }
    }
  }
}
