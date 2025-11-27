package scala.meta.pc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SemanticdbFileManager {
  List<SemanticdbCompilationUnit> listPackage(String pkg);

  Map<String, Set<Path>> listAllPackages();
}
