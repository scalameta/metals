package scala.meta.pc;

/**
 * All known code action Ids, this is not a complete list, new code actions might be added by
 * clients, which is why this is not an enum
 */
public class CodeActionId {
  public static final String ConvertToNamedArguments = "ConvertToNamedArguments";
  public static final String ExtractMethod = "ExtractMethod";
  public static final String ImplementAbstractMembers = "ImplementAbstractMembers";
  public static final String ImportMissingSymbol = "ImportMissingSymbol";
  public static final String InlineValue = "InlineValue";
  public static final String InsertInferredType = "InsertInferredType";
  public static final String InsertInferredMethod = "InsertInferredMethod";
}
