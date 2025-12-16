package scala.meta.pc;

import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;

@FunctionalInterface
public interface JavaFileManagerFactory {
  JavaFileManager createFileManager(StandardJavaFileManager standardFileManager);

  public static final JavaFileManagerFactory EMPTY = standardFileManager -> standardFileManager;
}
