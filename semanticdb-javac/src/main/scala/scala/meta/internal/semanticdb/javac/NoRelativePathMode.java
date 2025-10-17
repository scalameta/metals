package scala.meta.internal.semanticdb.javac;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Configuration options to determine how semanticdb-javac should handle files
 * that have no good relative paths.
 *
 * <p>
 * When indexing a file at an absolute path /project/src/main/example/Foo.java,
 * SemanticDB needs to know the "sourceroot" path /project in order to
 * relativize the path of the source file into src/main/example/Foo.java. This
 * configuration option determines what the compiler plugin should do when it's
 * not able to find a good relative path.
 */
public enum NoRelativePathMode {

	/**
	 * Come up with a unique relative path for the SemanticDB path with no guarantee
	 * that this path corresponds to the original path of the generated source file.
	 */
	INDEX_ANYWAY,

	/** Silently ignore the file, don't index it. */
	SKIP,

	/** Report an error message and fail the compilation. */
	ERROR,

	/** Ignore the file, but print a message explaining it's ignored. */
	WARNING;

	public static String validStringValuesWithoutError() {
		return Arrays.stream(NoRelativePathMode.values()).filter(mode -> !mode.equals(ERROR))
				.map(NoRelativePathMode::toString).collect(Collectors.joining(", "));
	}

	public static String validStringValues() {
		return Arrays.stream(NoRelativePathMode.values()).map(NoRelativePathMode::toString)
				.collect(Collectors.joining(", "));
	}
}
