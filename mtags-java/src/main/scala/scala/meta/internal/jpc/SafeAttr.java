package scala.meta.internal.jpc;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/**
 * A safer version of Attr that handles edge cases during tree attribution.
 *
 * <p>During IDE use cases (completions, hovers, indexing, etc.), we may encounter malformed or
 * incomplete code that triggers exceptions in javac. This class provides targeted fixes for known
 * problematic cases, following NetBeans' NBAttr approach.
 */
public class SafeAttr extends Attr {

  public static void preRegister(Context context) {
    context.put(
        attrKey,
        new Context.Factory<Attr>() {
          @Override
          public Attr make(Context c) {
            return new SafeAttr(c);
          }
        });
  }

  private final Context context;

  public SafeAttr(Context context) {
    super(context);
    this.context = context;
  }

  @Override
  public void attribClass(DiagnosticPosition pos, ClassSymbol c) {
    try {
      super.attribClass(pos, c);
    } catch (NullPointerException | AssertionError e) {
      c.type = Symtab.instance(context).errorType;

      // Do nothing instead of throwing AssertionError.
      // The class will remain unattributed, but compilation can continue.
    }
  }

  /**
   * Override the default fallback visitor to not throw AssertionError.
   *
   * <p>In standard javac, visitTree throws AssertionError because reaching it means an unexpected
   * tree type was encountered. However, in IDE scenarios with malformed or incomplete code, we may
   * encounter unexpected tree nodes. Instead of crashing, we simply do nothing and let attribution
   * continue.
   */
  @Override
  public void visitTree(JCTree tree) {
    // Do nothing instead of throwing AssertionError.
    // The tree will remain unattributed, but compilation can continue.
  }
}
