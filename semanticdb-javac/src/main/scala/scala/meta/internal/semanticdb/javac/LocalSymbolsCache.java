package scala.meta.internal.semanticdb.javac;

import java.util.IdentityHashMap;
import javax.lang.model.element.Element;

/** Cache of SemanticDB symbols that are local to a single file. */
public final class LocalSymbolsCache {

  private final IdentityHashMap<Element, String> symbols = new IdentityHashMap<>();
  private int localsCounter = -1;

  public String get(Element sym) {
    return symbols.get(sym);
  }

  public String put(Element sym) {
    localsCounter++;
    String result = SemanticdbSymbols.local(localsCounter);
    symbols.put(sym, result);
    return result;
  }
}
