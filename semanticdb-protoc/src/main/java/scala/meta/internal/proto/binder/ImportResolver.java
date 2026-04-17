package scala.meta.internal.proto.binder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import scala.meta.internal.proto.diag.ProtoError;
import scala.meta.internal.proto.diag.SourceFile;
import scala.meta.internal.proto.parse.Parser;
import scala.meta.internal.proto.tree.Proto.ImportDecl;
import scala.meta.internal.proto.tree.Proto.ProtoFile;

/**
 * Resolves imports in protobuf files.
 *
 * <p>Similar to Turbine's approach for Java, this resolver works by: 1. Maintaining a registry of
 * all .proto files in the workspace 2. When resolving `import "path/to/file.proto"`, searching for
 * a file whose path ends with the import path 3. This provides an out-of-the-box experience without
 * requiring explicit import path configuration
 */
public final class ImportResolver {

  private final Function<String, Path> protoFileRegistry;
  private final Map<String, ProtoFile> cache;

  /**
   * Create an ImportResolver with a registry function.
   *
   * @param protoFileRegistry A function that takes an import path (e.g., "semanticdb.proto") and
   *     returns the Path to the file, or null if not found. The implementation should search all
   *     .proto files in the workspace for one whose path ends with the given import path.
   */
  public ImportResolver(Function<String, Path> protoFileRegistry) {
    this.protoFileRegistry = protoFileRegistry;
    this.cache = new HashMap<>();
  }

  /**
   * Resolve an import declaration to a parsed ProtoFile.
   *
   * @param importDecl The import declaration from the AST
   * @param sourceFile The file containing the import (used for relative imports)
   * @return The parsed imported file, or null if not found
   */
  public ProtoFile resolveImport(ImportDecl importDecl, Path sourceFile) {
    String importPath = importDecl.path();

    // Check cache first
    if (cache.containsKey(importPath)) {
      return cache.get(importPath);
    }

    // Try to find the file
    Path resolvedPath = findImportedFile(importPath, sourceFile);
    if (resolvedPath == null) {
      return null;
    }

    // Parse the imported file
    try {
      String content = Files.readString(resolvedPath);
      SourceFile source = new SourceFile(resolvedPath.toString(), content);
      ProtoFile file = Parser.parse(source);
      cache.put(importPath, file);
      return file;
    } catch (IOException e) {
      return null;
    } catch (ProtoError e) {
      // Parse error in imported file - ignore for now
      return null;
    }
  }

  /**
   * Resolve an import declaration to a file path (for go-to-definition).
   *
   * @param importDecl The import declaration from the AST
   * @param sourceFile The file containing the import (used for relative imports)
   * @return The path to the imported file, or null if not found
   */
  public Path resolveImportPath(ImportDecl importDecl, Path sourceFile) {
    String importPath = importDecl.path();
    return findImportedFile(importPath, sourceFile);
  }

  /**
   * Find an imported file by: 1. Checking relative to the source file's directory (for
   * same-directory imports) 2. Walking up parent directories to find proto roots that contain the
   * import path 3. Using the registry to search all workspace .proto files
   *
   * <p>This matches how protoc resolves imports: paths are relative to proto include roots. For
   * example, if you have: src/main/proto/ types/timestamp.proto events/event.proto (imports
   * "types/timestamp.proto")
   *
   * <p>The import "types/timestamp.proto" is resolved relative to src/main/proto/
   */
  private Path findImportedFile(String importPath, Path sourceFile) {
    if (sourceFile != null) {
      Path sourceDir = sourceFile.getParent();
      if (sourceDir != null) {
        // Try relative to source file's directory (same-directory imports)
        Path candidate = sourceDir.resolve(importPath);
        if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
          return candidate;
        }

        // Walk up parent directories to find a proto root that contains the import.
        // This handles cross-subdirectory imports like:
        //   import "types/timestamp.proto" from events/event.proto
        // We walk up from events/ to find the parent that contains types/timestamp.proto
        Path current = sourceDir;
        while (current != null && current.getNameCount() > 0) {
          current = current.getParent();
          if (current != null) {
            candidate = current.resolve(importPath);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
              return candidate;
            }
          }
        }

        // Additional strategy: search for a proto file whose path ends with the import path.
        // This handles cases where the import is a partial path like "api/messages/common.proto"
        // and we need to find it somewhere in the parent directories.
        // Walk up from source directory, and at each level, try to find a file that ends with
        // the import path.
        current = sourceDir;
        while (current != null && current.getNameCount() > 0) {
          current = current.getParent();
          if (current != null) {
            Path found = searchDirectoryForImport(current, importPath);
            if (found != null) {
              return found;
            }
          }
        }
      }
    }

    // Use the registry to search all workspace proto files
    return protoFileRegistry.apply(importPath);
  }

  /**
   * Search a directory tree for a file whose path ends with the import path. This handles imports
   * like "api/messages/common.proto" when the actual file might be at
   * "cluster-common/api/messages/common.proto".
   */
  private Path searchDirectoryForImport(Path directory, String importPath) {
    if (!Files.isDirectory(directory)) {
      return null;
    }

    // Only search one level of directories to avoid walking the entire filesystem
    // This is a heuristic that handles common monorepo patterns
    try (var stream = Files.list(directory)) {
      return stream
          .filter(Files::isDirectory)
          .map(subDir -> subDir.resolve(importPath))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** Clear the cache (useful for testing). */
  public void clearCache() {
    cache.clear();
  }
}
