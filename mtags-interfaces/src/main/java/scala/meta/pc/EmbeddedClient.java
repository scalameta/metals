package scala.meta.pc;

import java.nio.file.Path;

/**
 * An interface to access embedded/vendored artifacts or resources like a temporary directory to
 * generate files in.
 */
public interface EmbeddedClient {

  default Path javaHeaderCompilerPluginJarPath() {
    return null;
  }

  default Path semanticdbJavacPluginJarPath() {
    return null;
  }

  default Path targetDir() {
    return null;
  }

  default Path jdkSourcesReadonlyDir() {
    return null;
  }
}
