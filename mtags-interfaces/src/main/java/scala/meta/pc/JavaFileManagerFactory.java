package scala.meta.pc;

import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;

@FunctionalInterface
public interface JavaFileManagerFactory {
  JavaFileManager createFileManager(
      StandardJavaFileManager standardFileManager, List<Path> classpath);

  public static final JavaFileManagerFactory EMPTY =
      (standardFileManager, cp) -> standardFileManager;
}
