package scala.meta.pc;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public interface SemanticdbFileManager {
  default Map<String, Set<Path>> listAllPackages() {
    return Collections.emptyMap();
  }

  public static final SemanticdbFileManager EMPTY = new SemanticdbFileManager() {};
}
