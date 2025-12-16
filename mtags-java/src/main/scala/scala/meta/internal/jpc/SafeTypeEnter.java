package scala.meta.internal.jpc;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.TypeEnter;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.FatalError;

/**
 * A safer version of TypeEnter that catches additional exceptions during type entry.
 *
 * <p>The standard TypeEnter only catches CompletionFailure. However, during IDE use cases
 * (indexing, completions, hovers, etc.), we may encounter malformed or incomplete code that
 * triggers other exceptions like NullPointerException during import resolution. This class catches
 * those at the complete level, allowing compilation to continue gracefully.
 */
public class SafeTypeEnter extends TypeEnter {

  public static void preRegister(Context context) {
    context.put(
        typeEnterKey,
        new Context.Factory<TypeEnter>() {
          @Override
          public TypeEnter make(Context c) {
            return new SafeTypeEnter(c);
          }
        });
  }

  private final Context context;

  public SafeTypeEnter(Context context) {
    super(context);
    this.context = context;
  }

  @Override
  public void complete(Symbol sym) {
    try {
      super.complete(sym);
    } catch (NullPointerException
        | AssertionError
        | ArrayIndexOutOfBoundsException
        | FatalError ex) {
      // In IDE scenarios, we may encounter malformed trees or incomplete code
      // (e.g., unresolved imports) that triggers unexpected exceptions during
      // import resolution. Rather than crashing, we mark the class type as
      // erroneous and continue.
      if (sym instanceof ClassSymbol) {
        // Lazily get Symtab here (not in constructor) to avoid initialization order issues
        sym.type = Symtab.instance(context).errType;
      }
    }
  }
}
