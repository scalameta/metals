package scala.meta.internal.bsp.sync;

import ch.epfl.scala.bsp4j.BuildTargetEvent;
import java.util.List;

public class WorkspaceSyncResult {
  private final List<BuildTargetEvent> changes;

  public WorkspaceSyncResult(List<BuildTargetEvent> changes) {
    this.changes = changes;
  }

  public List<BuildTargetEvent> getChanges() {
    return changes;
  }
}
