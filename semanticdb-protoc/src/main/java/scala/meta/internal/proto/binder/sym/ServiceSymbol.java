package scala.meta.internal.proto.binder.sym;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import scala.meta.internal.proto.tree.Proto.ServiceDecl;

/** Symbol representing a protobuf service. */
public final class ServiceSymbol extends Symbol {

  private final List<RpcSymbol> rpcs = new ArrayList<>();

  public ServiceSymbol(String name, String fullName, ServiceDecl node, Symbol owner) {
    super(name, fullName, node, owner);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.SERVICE;
  }

  @Override
  public String semanticdbSymbol() {
    // Service symbols use owner's symbol + service name with # suffix
    // e.g., "foo/bar/Greeter#" for service foo.bar.Greeter
    Symbol owner = owner();
    if (owner == null) {
      // Top-level service - use fullName() which includes the package
      return fullName().replace('.', '/') + "#";
    }
    return owner.semanticdbSymbol() + name() + "#";
  }

  public void addRpc(RpcSymbol rpc) {
    rpcs.add(rpc);
  }

  public ImmutableList<RpcSymbol> rpcs() {
    return ImmutableList.copyOf(rpcs);
  }

  /** Find an RPC method by name. */
  public RpcSymbol findRpc(String name) {
    for (RpcSymbol r : rpcs) {
      if (r.name().equals(name)) return r;
    }
    return null;
  }

  @Override
  public ServiceDecl node() {
    return (ServiceDecl) super.node();
  }
}
