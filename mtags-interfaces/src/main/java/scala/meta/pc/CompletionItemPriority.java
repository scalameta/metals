package scala.meta.pc;

public interface CompletionItemPriority {

    Integer workspaceMemberPriority(String buildTargetIdentifier, String symbol);
}
