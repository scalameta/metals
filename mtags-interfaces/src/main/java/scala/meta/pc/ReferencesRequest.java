package scala.meta.pc;

import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public interface ReferencesRequest {
  VirtualFileParams file();

  boolean includeDefinition();

  Either<Integer, String> offsetOrSymbol();

  default List<String> alternativeSymbols() {
    return Collections.emptyList();
  }
}
