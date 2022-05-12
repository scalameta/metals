package scala.meta.pc;

import org.eclipse.lsp4j.Range;

public interface ParamNameHintResult {
  public Range range();
  public String contentText();
}

