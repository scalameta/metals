package scala.meta.internal.semanticdb.javac;

import java.nio.file.Path;

public class TargetPaths {
  public Path classes;
  public Path sources;

  public TargetPaths(Path classesDir, Path sourcesDir) {
    classes = classesDir;
    sources = sourcesDir;
  }
}
