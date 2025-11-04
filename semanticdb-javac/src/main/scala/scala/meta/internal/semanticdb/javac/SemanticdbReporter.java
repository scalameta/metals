package scala.meta.internal.semanticdb.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.Trees;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import javax.tools.Diagnostic;

/**
 * Utilities to report error messages.
 *
 * <p>NOTE(olafur): this class exists because I couldn't find compiler APIs to report diagnostics.
 * This class can be removed if the Java compiler has APIs to report info/warning/error messages.
 */
public class SemanticdbReporter {
  private final Trees trees;

  public SemanticdbReporter(Trees trees) {
    this.trees = trees;
  }

  public void exception(Throwable e, Tree tree, CompilationUnitTree root) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter writer = new PrintWriter(baos);
    e.printStackTrace(writer);
    writer.println(
        "Please report a bug to https://github.com/sourcegraph/semanticdb-java with the stack trace"
            + " above.");
    trees.printMessage(Diagnostic.Kind.ERROR, baos.toString(), tree, root);
  }

  public void exception(Throwable e, TaskEvent task) {
    this.exception(e, task.getCompilationUnit(), task.getCompilationUnit());
  }

  public void info(String message, TaskEvent e) {
    trees.printMessage(
        Diagnostic.Kind.NOTE,
        "semanticdb-javac: " + message,
        e.getCompilationUnit(),
        e.getCompilationUnit());
  }

  public void error(String message, TaskEvent e) {
    trees.printMessage(
        Diagnostic.Kind.ERROR,
        "semanticdb-javac: " + message,
        e.getCompilationUnit(),
        e.getCompilationUnit());
  }

  public void error(String message, Tree tree, CompilationUnitTree root) {
    // NOTE(olafur): ideally, this message should be reported as a compiler
    // diagnostic, but I dind't
    // find
    // the reporter API so the message goes to stderr instead for now.
    trees.printMessage(
        Diagnostic.Kind.ERROR, String.format("semanticdb-javac: %s", message), tree, root);
  }
}
