package scala.meta.pc;

/**
 * Represents an implicit class member that can be used for completions.
 */
public class ImplicitClassMemberResult {
    public final String methodSymbol;
    public final String methodName;
    public final String classSymbol;

    public ImplicitClassMemberResult(String methodSymbol, String methodName, String classSymbol) {
        this.methodSymbol = methodSymbol;
        this.methodName = methodName;
        this.classSymbol = classSymbol;
    }
}

