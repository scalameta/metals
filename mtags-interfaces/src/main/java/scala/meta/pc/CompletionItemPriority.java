package scala.meta.pc;

public interface CompletionItemPriority {

  /**
   * Returns an integer that is used to sort workspaceMembers with the same name. A lower number
   * indicates a higher priority.
   */
  Integer workspaceMemberPriority(String symbol);
}
