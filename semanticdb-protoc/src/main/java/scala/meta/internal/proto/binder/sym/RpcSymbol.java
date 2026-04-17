package scala.meta.internal.proto.binder.sym;

import scala.meta.internal.proto.tree.Proto.RpcDecl;

/** Symbol representing a protobuf RPC method. */
public final class RpcSymbol extends Symbol {

  private Symbol inputTypeSymbol;
  private Symbol outputTypeSymbol;

  public RpcSymbol(String name, String fullName, RpcDecl node, Symbol owner) {
    super(name, fullName, node, owner);
  }

  @Override
  public SymbolKind kind() {
    return SymbolKind.RPC;
  }

  @Override
  public String semanticdbSymbol() {
    // RPC symbols use slash format with () suffix (method)
    // e.g., "foo/bar/Greeter#SayHello()." for rpc foo.bar.Greeter.SayHello
    String ownerSym = owner().semanticdbSymbol();
    return ownerSym + name() + "().";
  }

  public Symbol inputTypeSymbol() {
    return inputTypeSymbol;
  }

  public void setInputTypeSymbol(Symbol inputTypeSymbol) {
    this.inputTypeSymbol = inputTypeSymbol;
  }

  public Symbol outputTypeSymbol() {
    return outputTypeSymbol;
  }

  public void setOutputTypeSymbol(Symbol outputTypeSymbol) {
    this.outputTypeSymbol = outputTypeSymbol;
  }

  @Override
  public RpcDecl node() {
    return (RpcDecl) super.node();
  }
}
