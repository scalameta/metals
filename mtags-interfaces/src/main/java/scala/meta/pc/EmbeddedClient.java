package scala.meta.pc;

import java.util.List;
import java.nio.file.Path;
import java.util.Collections;

/**
 * An interface to access embedded/vendored artifacts or resources like a
 * temporary directory to generate files in.
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