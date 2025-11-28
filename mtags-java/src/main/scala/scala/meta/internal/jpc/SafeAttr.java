package scala.meta.internal.jpc;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/**
 * A safer version of Attr that catches additional exceptions during tree attribution.
 *
 * <p>The standard Attr only catches CompletionFailure in attribTree. However, during IDE use cases
 * (completions, hovers, etc.), we may encounter malformed or incomplete code that triggers other
 * exceptions like NullPointerException or AssertionError. This class catches those at the
 * attribClass level, allowing compilation to continue gracefully.
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

  private final Symtab symtab;

  public SafeAttr(Context context) {
    super(context);
    this.symtab = Symtab.instance(context);
  }

  @Override
  public void attribClass(DiagnosticPosition pos, ClassSymbol c) {
    try {
      super.attribClass(pos, c);
    } catch (NullPointerException | AssertionError | ArrayIndexOutOfBoundsException ex) {
      // In IDE scenarios, we may encounter malformed trees or incomplete code
      // that triggers unexpected exceptions. Rather than crashing, we mark
      // the class type as erroneous and continue.
      c.type = symtab.errType;
    }
  }
}
