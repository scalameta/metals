package metaserver;

import org.eclipse.lsp4j.services.WorkspaceService;
import org.eclipse.lsp4j.*;

import java.util.concurrent.CompletableFuture;
import java.util.List;

import scala.meta.languageserver.ScalametaLanguageServer;

public class ScalametaWorkspaceService implements WorkspaceService {

  private ScalametaLanguageServer server;

  public ScalametaWorkspaceService(ScalametaLanguageServer server) {
    this.server = server;
  }

  @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    server.didChangeWatchedFiles(params);
  }

  @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {
    server.didChangeConfiguration(params);
  }

  @Override public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
    return server.symbol(params);
  }

}
