package scala.meta.pc;

import java.util.List;
import org.eclipse.lsp4j.Location;

public interface DefinitionResult {
    String symbol();
    List<Location> locations();
}