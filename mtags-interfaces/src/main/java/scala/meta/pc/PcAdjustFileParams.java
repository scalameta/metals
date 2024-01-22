package scala.meta.pc;

import org.eclipse.lsp4j.Location;

public interface PcAdjustFileParams {
  VirtualFileParams params();
  Location adjustLocation(Location location);
}
