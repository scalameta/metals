package scala.meta.pc;

import java.util.List;
import java.net.URI;

public interface ReferencesRequest {
  OffsetParams params();
  boolean includeDefinition();
  List<URI> targetUris();
}
