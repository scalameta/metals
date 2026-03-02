package scala.meta.internal.bsp.sync;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

public interface SyncBuildServer {
  @JsonRequest("workspace/sync")
  public CompletableFuture<WorkspaceSyncResult> workspaceSync(WorkspaceSyncParams params);
}
