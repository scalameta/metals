package scala.meta.pc;

import java.util.List;

public interface SemanticdbFileManager {
  List<SemanticdbCompilationUnit> listPackage(String pkg);
}
