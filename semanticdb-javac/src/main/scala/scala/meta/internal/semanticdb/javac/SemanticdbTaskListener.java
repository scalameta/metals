package scala.meta.internal.semanticdb.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import scala.meta.internal.jsemanticdb.Semanticdb;

/**
 * Callback hook that generates SemanticDB when the compiler has completed typechecking a Java
 * source file.
 */
public final class SemanticdbTaskListener implements TaskListener {
  private final SemanticdbJavacOptions options;
  private final GlobalSymbolsCache globals;
  private final SemanticdbReporter reporter;
  private final Types types;
  private final Trees trees;
  private final Elements elements;
  private int noRelativePathCounter = 0;

  public SemanticdbTaskListener(
      SemanticdbJavacOptions options,
      JavacTask task,
      Trees trees,
      GlobalSymbolsCache globals,
      SemanticdbReporter reporter) {
    this.options = options;
    this.globals = globals;
    this.reporter = reporter;
    this.types = task.getTypes();
    this.trees = trees;
    this.elements = task.getElements();
  }

  @Override
  public void started(TaskEvent e) {
    // Upon first encounter with a file (before any other tasks are run)
    // we remove the semanticdb file for this source file to ensure
    // stale data doesn't cause problems
    if (e.getKind() == TaskEvent.Kind.ENTER) {
      inferBazelSourceroot(e.getSourceFile());
      Result<Path, String> semanticdbPath = semanticdbOutputPath(options, e);
      if (semanticdbPath.isOk()) {
        try {
          Files.deleteIfExists(semanticdbPath.getOrThrow());
        } catch (IOException ex) {
          this.reportException(ex, e);
        }
      }
    }
  }

  @Override
  public void finished(TaskEvent e) {
    if (e.getKind() != TaskEvent.Kind.ANALYZE) return;
    if (!options.errors.isEmpty()) {
      if (!options.alreadyReportedErrors) {
        options.alreadyReportedErrors = true;
        for (String error : options.errors) {
          reporter.error(error, e);
        }
      }
      return;
    }
    inferBazelSourceroot(e.getSourceFile());
    try {
      onFinishedAnalyze(e);
    } catch (Throwable ex) {
      // Catch exceptions because we don't want to stop the compilation even if this
      // plugin has a
      // bug. We report the full stack trace because it's helpful for bug reports.
      // Exceptions
      // should only happen in *exceptional* situations and they should be reported
      // upstream.
      Throwable throwable = ex;
      if (e.getSourceFile() != null) {
        throwable =
            new CompilationUnitException(
                String.valueOf(e.getSourceFile().toUri().toString()), throwable);
      }
      this.reportException(throwable, e);
    }
  }

  // Uses reporter.error with the full stack trace of the exception instead of
  // reporter.exception
  // because reporter.exception doesn't seem to print any meaningful information
  // about the
  // exception, it just prints the location with an empty message.
  private void reportException(Throwable exception, TaskEvent e) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter pw = new PrintWriter(baos);
    exception.printStackTrace(pw);
    pw.close();
    reporter.error(baos.toString(), e.getCompilationUnit(), e.getCompilationUnit());
  }

  private void onFinishedAnalyze(TaskEvent e) {
    Result<Path, String> path = semanticdbOutputPath(options, e);
    if (path != null) {
      if (path.isOk()) {
        Semanticdb.TextDocument textDocument =
            new SemanticdbVisitor(globals, e.getCompilationUnit(), options, types, trees, elements)
                .buildTextDocument(e.getCompilationUnit());
        Path output = path.getOrThrow();
        if (Files.exists(output)) appendSemanticdb(e, output, textDocument);
        else writeSemanticdb(e, output, textDocument);
      } else {
        reporter.error(path.getErrorOrThrow(), e);
      }
    }
  }

  private void writeSemanticdb(TaskEvent event, Path output, Semanticdb.TextDocument textDocument) {
    try {
      byte[] bytes =
          Semanticdb.TextDocuments.newBuilder().addDocuments(textDocument).build().toByteArray();
      Files.createDirectories(output.getParent());
      Files.write(output, bytes);
    } catch (IOException e) {
      this.reportException(e, event);
    }
  }

  private void appendSemanticdb(
      TaskEvent event, Path output, Semanticdb.TextDocument textDocument) {
    /*
     * If there already is a semanticdb file at the given path, we do the following:
     * - Read a documents collection - Try to find the document with the matching
     * relative path (matching the incoming textDocument) - Then, depending on
     * whether a matching document already exists in the collection: - if YES,
     * mutate it in place to only add entries from the incoming document - if NO,
     * simply add the incoming text document to the collection - Write the
     * collection back to disk
     */
    Semanticdb.TextDocument document = null;
    int documentIndex = -1;
    Semanticdb.TextDocuments documents = null;

    try (InputStream is = Files.newInputStream(output.toFile().toPath())) {
      documents = Semanticdb.TextDocuments.parseFrom(is);

      for (int i = 0; i < documents.getDocumentsCount(); i++) {
        Semanticdb.TextDocument candidate = documents.getDocuments(i);
        if (document == null && candidate.getUri().equals(textDocument.getUri())) {
          document = candidate;
          documentIndex = i;
        }
      }

    } catch (IOException e) {
      this.reportException(e, event);
      return;
    }

    if (document != null) {
      // If there is a previous semanticdb document at this path, we need
      // to deduplicate symbols and occurrences and mutate the document in place
      Set<Semanticdb.SymbolInformation> symbols = new HashSet<>(textDocument.getSymbolsList());
      Set<Semanticdb.SymbolOccurrence> occurrences =
          new HashSet<>(textDocument.getOccurrencesList());
      Set<Semanticdb.Synthetic> synthetics = new HashSet<>(textDocument.getSyntheticsList());

      symbols.addAll(document.getSymbolsList());
      occurrences.addAll(document.getOccurrencesList());
      synthetics.addAll(document.getSyntheticsList());

      documents.toBuilder()
          .addDocuments(
              documentIndex,
              document.toBuilder()
                  .clearOccurrences()
                  .addAllOccurrences(occurrences)
                  .clearSymbols()
                  .addAllSymbols(symbols)
                  .clearSynthetics()
                  .addAllSynthetics(synthetics));

    } else {
      // If no prior document was found, we can just add the incoming one to the
      // collection
      documents = documents.toBuilder().addDocuments(textDocument).build();
    }

    byte[] bytes = documents.toByteArray();

    try {
      Files.createDirectories(output.getParent());
      Files.write(output, bytes);
    } catch (IOException e) {
      this.reportException(e, event);
    }
  }

  public static Path absolutePathFromUri(SemanticdbJavacOptions options, JavaFileObject file) {
    URI uri = file.toUri();
    if (options.uriScheme == UriScheme.ZINC
        && uri.getScheme().equals("vf")
        && uri.toString().startsWith("vf://tmp/")) {
      String[] parts = uri.toString().split("/", 5);
      if (parts.length == 5) {
        return options.sourceroot.resolve(Paths.get(parts[4]));
      } else {
        throw new IllegalArgumentException("unsupported URI: " + uri);
      }
    } else if (options.uriScheme == UriScheme.BAZEL) {
      String toString = file.toString().replace(":", "/");
      // This solution is hacky, and it would be very nice to use a dedicated API
      // instead.
      // The Bazel Java compiler constructs `SimpleFileObject/DirectoryFileObject`
      // with a
      // "user-friendly" name that points to the original source file and an
      // underlying/actual
      // file path in a temporary directory. We're constrained by having to use only
      // public APIs of
      // the Java compiler and `toString()` seems to be the only way to access the
      // user-friendly
      // path.
      String[] knownBazelToStringPatterns =
          new String[] {"SimpleFileObject[", "DirectoryFileObject["};
      for (String pattern : knownBazelToStringPatterns) {
        if (toString.startsWith(pattern) && toString.endsWith("]")) {
          Path path = Paths.get(toString.substring(pattern.length(), toString.length() - 1));
          if (path.isAbsolute()) {
            return path;
          }
          return options.sourceroot.resolve(path);
        }
      }
    } else if (file.getName().startsWith("originaluri-")) {
      // NOTE(olafurpg): see SourceJavaFileObject.makeOriginalURI() for an
      // explanation behind the "originaluri-" prefix.
      uri = URI.create(file.getName().substring("originaluri-".length()));
    }

    // Fallback to default behavior.
    return Paths.get(uri);
  }

  // Infers the `-sourceroot:` flag from the provided file.
  // FIXME: add unit tests https://github.com/sourcegraph/scip-java/issues/444
  private void inferBazelSourceroot(JavaFileObject file) {
    if (options.uriScheme != UriScheme.BAZEL || options.sourceroot != null) {
      return;
    }
    Path absolutePath = absolutePathFromUri(options, file);
    Path uriPath = Paths.get(file.toUri());
    // absolutePath is the "human-readable" original path, for example
    // /home/repo/com/example/Hello.java
    // uriPath is the sandbox/temporary file path, for example
    // /private/var/tmp/com/example/Hello.java
    //
    // We infer sourceroot by iterating the names of both files in reverse order
    // and stop at the first entry where the two paths are different. For the
    // example above, we compare "Hello.java", then "example", then "com", and
    // when we reach "repo" != "tmp" then we guess that "/home/repo" is the
    // sourceroot. This logic is brittle and it would be nice to use more
    // dedicated APIs, but Bazel actively makes an effort to sandbox
    // compilation and hide access to the original workspace, which is why we
    // resort to solutions like this.
    int relativePathDepth = 0;
    int uriPathDepth = uriPath.getNameCount();
    int absolutePathDepth = absolutePath.getNameCount();
    while (relativePathDepth < uriPathDepth && relativePathDepth < absolutePathDepth) {
      String uriName = uriPath.getName(uriPathDepth - relativePathDepth - 1).toString();
      String pathName = absolutePath.getName(absolutePathDepth - relativePathDepth - 1).toString();
      if (!uriName.equals(pathName)) {
        break;
      }
      relativePathDepth++;
    }
    options.sourceroot =
        absolutePath
            .getRoot()
            .resolve(absolutePath.subpath(0, absolutePathDepth - relativePathDepth));
  }

  private Result<Path, String> semanticdbOutputPath(SemanticdbJavacOptions options, TaskEvent e) {
    Path absolutePath = absolutePathFromUri(options, e.getSourceFile());
    if (absolutePath.startsWith(options.sourceroot)) {
      Path relativePath = options.sourceroot.relativize(absolutePath);
      String filename = relativePath.getFileName().toString() + ".semanticdb";
      Path semanticdbOutputPath =
          options
              .targetroot
              .resolve("META-INF")
              .resolve("semanticdb")
              .resolve(relativePath)
              .resolveSibling(filename);
      return Result.ok(semanticdbOutputPath);
    }

    switch (options.noRelativePath) {
      case INDEX_ANYWAY:
        // Come up with a unique relative path for this file even if it's not under the
        // sourceroot.
        // By indexing auto-generated files, we collect SymbolInformation for
        // auto-generated symbol,
        // which results in more useful hover tooltips in the editor.
        // In the future, we may want to additionally embed the full text contents of
        // these files
        // so that it's possible to browse generated files with precise code navigation.
        String uniqueFilename =
            String.format("%d.%s.semanticdb", ++noRelativePathCounter, absolutePath.getFileName());
        Path semanticdbOutputPath =
            options
                .targetroot
                .resolve("META-INF")
                .resolve("semanticdb")
                .resolve("no-relative-path")
                .resolve(uniqueFilename);
        return Result.ok(semanticdbOutputPath);
      case WARNING:
        reporter.info(
            String.format(
                "Skipping file '%s' because it is not under the sourceroot '%s'",
                absolutePath, options.sourceroot),
            e);
      case SKIP:
        return null;
      case ERROR:
      default:
        String baseMessage =
            String.format(
                "Unable to detect the relative path of '%s'. A common reason for this error is that"
                    + " the file is that this file is auto-generated. To fix this problem update"
                    + " the flag -no-relative-path:VALUE to have one of the following values: %s.",
                absolutePath, NoRelativePathMode.validStringValuesWithoutError());
        if (options.uriScheme == UriScheme.BAZEL) {
          return Result.error(baseMessage);
        }

        return Result.error(
            baseMessage
                + " Alternatively, configure the -sourceroot:PATH flag to point to a directory path"
                + " that is the parent of all indexed files.");
    }
  }
}
