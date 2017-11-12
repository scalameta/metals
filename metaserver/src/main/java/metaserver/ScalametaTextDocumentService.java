package metaserver;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.concurrent.CompletableFuture;
import java.util.List;

import scala.meta.languageserver.ScalametaLanguageServer;

public class ScalametaTextDocumentService implements TextDocumentService {

  private ScalametaLanguageServer server;

  public ScalametaTextDocumentService(ScalametaLanguageServer server) {
    this.server = server;
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(TextDocumentPositionParams position) {
     return server.completion(position);
  }

  @Override
  public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
    return server.resolveCompletionItem(unresolved);
  }

  @Override
  public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
    return server.hover(position);
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
    return server.signatureHelp(position);
  }

  @Override
  public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
    return server.definition(position);
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    return server.references(params);
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams position) {
    return server.documentHighlight(position);
  }

  @Override
  public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
    return server.documentSymbol(params);
  }

  @Override
  public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
    return server.codeAction(params);
  }

  @Override
  public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
    return server.codeLens(params);
  }

  @Override
  public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
    return server.resolveCodeLens(unresolved);
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
    return server.formatting(params);
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
    return server.rangeFormatting(params);
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
    return server.onTypeFormatting(params);
  }

  @Override
  public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
    return server.documentLink(params);
  }

  @Override
  public CompletableFuture<DocumentLink> documentLinkResolve(DocumentLink params) {
    return server.documentLinkResolve(params);
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
    return server.rename(params);
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    server.didOpen(params);
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    server.didChange(params);
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    server.didClose(params);
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    server.didSave(params);
  }

}
