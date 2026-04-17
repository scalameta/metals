package scala.meta.internal.proto.binder;

import java.util.HashMap;
import java.util.Map;
import scala.meta.internal.proto.binder.sym.*;
import scala.meta.internal.proto.tree.Proto;

/**
 * Symbol table for protobuf files.
 *
 * <p>Stores all symbols defined in a proto file and provides lookup functionality. Symbols can be
 * looked up by fully qualified name or by AST node.
 */
public final class SymbolTable {

  // Symbol lookup by fully qualified name
  private final Map<String, Symbol> symbolsByName = new HashMap<>();

  // Symbol lookup by AST node
  private final Map<Proto, Symbol> symbolsByNode = new HashMap<>();

  // Package symbol, if any
  private PackageSymbol packageSymbol;

  /** Register a symbol in the table. */
  public void register(Symbol symbol) {
    symbolsByName.put(symbol.fullName(), symbol);
    symbolsByNode.put(symbol.node(), symbol);
  }

  /** Set the package symbol. */
  public void setPackageSymbol(PackageSymbol packageSymbol) {
    this.packageSymbol = packageSymbol;
    if (packageSymbol != null) {
      register(packageSymbol);
    }
  }

  /** Get the package symbol, if any. */
  public PackageSymbol packageSymbol() {
    return packageSymbol;
  }

  /** Look up a symbol by fully qualified name. */
  public Symbol lookup(String fullName) {
    return symbolsByName.get(fullName);
  }

  /** Look up a symbol by AST node. */
  public Symbol lookup(Proto node) {
    return symbolsByNode.get(node);
  }

  /** Look up a type by name, searching in the given scope and parent scopes. */
  public Symbol resolveType(String typeName, Symbol scope) {
    // First check if it's a fully qualified name
    Symbol direct = lookup(typeName);
    if (direct != null) {
      return direct;
    }

    // Search in the current scope and parent scopes
    Symbol current = scope;
    while (current != null) {
      // Try prefixing with current scope's full name
      String candidate = current.fullName() + "." + typeName;
      Symbol found = lookup(candidate);
      if (found != null) {
        return found;
      }

      // For nested types, also check siblings
      if (current instanceof MessageSymbol) {
        MessageSymbol msg = (MessageSymbol) current;
        Symbol member = msg.findMember(typeName);
        if (member != null) {
          return member;
        }
      }

      current = current.owner();
    }

    // Try with package prefix and parent packages
    // This implements protobuf's package resolution: for a type reference like
    // "dbar.Request" from package "databricks.accountsadmin", we should try:
    // 1. databricks.accountsadmin.dbar.Request
    // 2. databricks.dbar.Request (parent package)
    // 3. dbar.Request (root)
    if (packageSymbol != null) {
      String pkgName = packageSymbol.fullName();
      while (!pkgName.isEmpty()) {
        String withPackage = pkgName + "." + typeName;
        Symbol found = lookup(withPackage);
        if (found != null) {
          return found;
        }
        // Move to parent package
        int lastDot = pkgName.lastIndexOf('.');
        if (lastDot < 0) {
          break;
        }
        pkgName = pkgName.substring(0, lastDot);
      }
    }

    // Not found
    return null;
  }

  /** Get all symbols. */
  public Iterable<Symbol> allSymbols() {
    return symbolsByName.values();
  }

  /** Get the number of symbols. */
  public int size() {
    return symbolsByName.size();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("SymbolTable(");
    sb.append(symbolsByName.size());
    sb.append(" symbols):\n");
    for (Symbol sym : symbolsByName.values()) {
      sb.append("  ").append(sym).append("\n");
    }
    return sb.toString();
  }
}
