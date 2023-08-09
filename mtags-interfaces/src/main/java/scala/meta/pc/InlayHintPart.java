package scala.meta.pc;

import org.eclipse.lsp4j.InlayHintLabelPart;


public interface InlayHintPart {
  String label();
  String symbol();
}