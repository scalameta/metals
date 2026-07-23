package scala.meta.pc;

import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.Location;

public interface DefinitionResult {
  static DefinitionResult empty() {
    return new DefinitionResult() {
      @Override
      public String symbol() {
        return "";
      }

      @Override
      public List<Location> locations() {
        return Collections.emptyList();
      }
    };
  }

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

  /**
   * The simple names of the resolved method's parameters, in declaration order, or empty if the
   * symbol isn't a method or this information isn't available.
   */
  default List<String> parameterNames() {
    return Collections.emptyList();
  }

  /**
   * The type names of the resolved method's parameters, in declaration order, or empty if the
   * symbol isn't a method or this information isn't available.
   */
  default List<String> parameterTypeNames() {
    return Collections.emptyList();
  }
}
