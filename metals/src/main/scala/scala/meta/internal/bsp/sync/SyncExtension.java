package scala.meta.internal.bsp.sync;

import java.util.List;

public class SyncExtension {
  public List<SyncMode> modes;

  public SyncExtension(List<SyncMode> modes) {
    this.modes = modes;
  }

  public List<SyncMode> getModes() {
    return modes;
  }
}
