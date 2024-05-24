package scala.meta.pc;

import java.util.List;
import java.net.URI;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public interface ReferencesRequest {
  VirtualFileParams file();
  boolean includeDefinition();
  Either<Integer, String> offsetOrSymbol();
}
