package scala.meta.pc;

import java.util.List;
import org.eclipse.lsp4j.Location;

public interface DefinitionResult {
  String symbol();

  List<Location> locations();

  /**
   * A resolved definition has full location information, while an unresolved definition may only
   * have a symbol and a uri, but no position. Currently, the only case where we return unresolved
   * definitions is when we are decompiling code from a jar file. The PC does not have access to a
   * decompiler, so it relies on the client to decompile and locate the symbol definition inside the
   * decompiled code.
   */
  default boolean isResolved() {
    return true;
  }
}
