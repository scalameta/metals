package scala.meta.internal.jpc;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.FatalError;

/**
 * A safer version of Enter that handles edge cases during tree entry.
 *
 * <p>During IDE use cases (completions, hovers, indexing, etc.), we may encounter malformed or
 * incomplete code that triggers exceptions in javac. This class provides targeted fixes for known
 * problematic cases, following NetBeans' NBEnter approach.
 *
 * <p>Known issues handled:
 *
 * <ul>
 *   <li>NullPointerException in visitTopLevel when currentPackage is null during package
 *       declaration processing.
 *   <li>FatalError in complete() when java.lang package cannot be found in classpath.
 * </ul>
 */
public class SafeEnter extends Enter {

  public static void preRegister(Context context) {
    context.put(
        enterKey,
        new Context.Factory<Enter>() {
          @Override
          public Enter make(Context c) {
            return new SafeEnter(c);
          }
        });
  }

  public SafeEnter(Context context) {
    super(context);
  }

  /**
   * Override visitTopLevel to catch NullPointerException and FatalError.
   *
   * <p>In standard javac, visitTopLevel can throw NullPointerException when processing package
   * declarations if currentPackage is null (e.g., "Cannot read field 'type' because
   * 'this.currentPackage' is null"). In IDE scenarios with malformed or incomplete code, we catch
   * this exception and allow compilation to continue.
   */
  @Override
  public void visitTopLevel(JCCompilationUnit tree) {
    try {
      super.visitTopLevel(tree);
    } catch (NullPointerException | AssertionError | FatalError e) {
      // Do nothing instead of propagating the exception.
      // The compilation unit will remain partially processed, but compilation can continue.
    }
  }

  /**
   * Override complete to catch FatalError.
   *
   * <p>In standard javac, complete() can throw FatalError when essential packages like java.lang
   * cannot be found in the classpath (e.g., "Fatal Error: Unable to find package java.lang in
   * classpath or bootclasspath"). In IDE scenarios, we catch this exception and allow compilation
   * to continue gracefully.
   */
  @Override
  public void complete(
      com.sun.tools.javac.util.List<JCCompilationUnit> trees, Symbol.ClassSymbol c) {
    try {
      super.complete(trees, c);
    } catch (NullPointerException | AssertionError | FatalError e) {
      // Do nothing instead of propagating the exception.
      // The symbol will remain incomplete, but compilation can continue.
    }
  }
}
