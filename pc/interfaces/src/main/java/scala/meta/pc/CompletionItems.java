package scala.meta.pc;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;

import java.util.List;

public class CompletionItems extends CompletionList {

    public LookupKind lookupKind;

    public CompletionItems(LookupKind lookupKind, List<CompletionItem> items) {
        super();
        this.lookupKind = lookupKind;
        super.setItems(items);
    }

    public enum LookupKind {
        None,
        Scope,
        Type
    }
}
