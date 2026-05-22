# OTEL Tracing POC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add no-op-by-default OTEL tracing spans to LSP requests and BSP calls.

**Architecture:** A `MetalsTracer` utility wraps `GlobalOpenTelemetry.get()` (no-op by default). A `TracedScalaLspService` decorator wraps every LSP handler with a span. `BuildServerConnection.register()` creates spans for BSP calls using ClassTag type names.

**Tech Stack:** io.opentelemetry:opentelemetry-api 1.58.0, sbt, Scala 2.13

---

### Task 1: Add OTEL API dependency

**Files:**
- Modify: `project/V.scala`
- Modify: `build.sbt`

- [ ] **Step 1: Add version constant to V.scala**

In `project/V.scala`, add after the existing version constants (after line 30):
```scala
val opentelemetry = "1.58.0"
```

- [ ] **Step 2: Add dependency to build.sbt**

In `build.sbt`, find the `metals` module dependencies section (around line 394+) and add:
```scala
"io.opentelemetry" % "opentelemetry-api" % V.opentelemetry,
```

- [ ] **Step 3: Run import-build to refresh dependencies**

Use the MCP `import-build` tool.

- [ ] **Step 4: Commit**

```bash
git add project/V.scala build.sbt
git commit -m "feat: add opentelemetry-api dependency"
```

---

### Task 2: Create MetalsTracer utility

**Files:**
- Create: `metals/src/main/scala/scala/meta/internal/metals/MetalsTracer.scala`

- [ ] **Step 1: Create the MetalsTracer object**

```scala
package scala.meta.internal.metals

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.context.Context

/**
 * Thin facade over the OpenTelemetry API.
 * 
 * When no OTEL SDK is on the classpath, GlobalOpenTelemetry.get() returns a
 * no-op provider and all spans are zero-cost no-ops.
 * 
 * To enable real tracing, add an OTEL Java agent or SDK+exporter JAR to the
 * editor's JVM arguments.
 */
object MetalsTracer {

  private val tracer: Tracer =
    GlobalOpenTelemetry.get().tracerBuilder("metals").build()

  def startSpan(name: String, attributes: (String, String)*): Span = {
    val builder = tracer.spanBuilder(name)
    attributes.foreach { case (k, v) => builder.setAttribute(k, v) }
    builder.startSpan()
  }

  def endSpan(span: Span): Unit = {
    span.end()
  }

  def addEvent(span: Span, event: String): Unit = {
    span.addEvent(event)
  }

  def recordException(span: Span, throwable: Throwable): Unit = {
    span.recordException(throwable)
    span.setStatus(StatusCode.ERROR)
  }

  def currentContext(): Context = {
    Context.current()
  }

  def withSpanContext(span: Span)(fn: => Unit): Unit = {
    val scope = span.makeCurrent()
    try fn
    finally scope.close()
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add metals/src/main/scala/scala/meta/internal/metals/MetalsTracer.scala
git commit -m "feat: add MetalsTracer utility wrapping OTEL API"
```

---

### Task 3: Create TracedScalaLspService decorator

**Files:**
- Create: `metals/src/main/scala/scala/meta/internal/metals/TracedScalaLspService.scala`

- [ ] **Step 1: Create the decorator class**

This class implements all four LSP service traits (`TextDocumentService`, `WorkspaceService`, `MetalsService`, `WindowService`) by delegating to an underlying `ScalaLspService`, wrapping each method with a span. Methods that don't carry a URI (notifications without text document context) get a minimal span. Control-plane methods (`didClose`, `didCancelWorkDoneProgress`) are left unwrapped.

```scala
package scala.meta.internal.metals

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.metals.lsp.{
  MetalsService,
  ScalaLspService,
  TextDocumentService,
  WindowService,
  WorkspaceService
}
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.tvp.{
  MetalsTreeViewChildrenResult,
  TreeViewChildrenParams,
  TreeViewNodeCollapseDidChangeParams,
  TreeViewNodeRevealResult,
  TreeViewParentParams,
  TreeViewParentResult,
  TreeViewVisibilityDidChangeParams
}

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

/**
 * Decorator that adds OpenTelemetry tracing spans to every LSP request.
 * Delegates all method calls to the underlying ScalaLspService.
 */
class TracedScalaLspService(
    underlying: ScalaLspService
) extends ScalaLspService {

  import MetalsTracer._

  private def traceRequest[T](
      method: String,
      attributes: (String, String)*
  )(action: => CompletableFuture[T]): CompletableFuture[T] = {
    val span = startSpan(method, attributes: _*)
    try {
      action.whenComplete { (_, error) =>
        if (error != null) recordException(span, error)
        endSpan(span)
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        recordException(span, e)
        endSpan(span)
        throw e
    }
  }

  private def uriAttr(params: TextDocumentPositionParams): (String, String) =
    "file.uri" -> params.getTextDocument.getUri

  // ---- TextDocumentService ----

  override def didOpen(params: DidOpenTextDocumentParams): CompletableFuture[Unit] =
    traceRequest("textDocument/didOpen", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didOpen(params)
    }

  override def didChange(params: DidChangeTextDocumentParams): CompletableFuture[Unit] =
    traceRequest("textDocument/didChange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didChange(params)
    }

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    underlying.didClose(params)

  override def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit] =
    traceRequest("textDocument/didSave", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didSave(params)
    }

  override def definition(params: TextDocumentPositionParams): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/definition", uriAttr(params)) {
      underlying.definition(params)
    }

  override def typeDefinition(params: TextDocumentPositionParams): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/typeDefinition", uriAttr(params)) {
      underlying.typeDefinition(params)
    }

  override def implementation(params: TextDocumentPositionParams): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/implementation", uriAttr(params)) {
      underlying.implementation(params)
    }

  override def hover(params: HoverExtParams): CompletableFuture[Hover] =
    traceRequest("textDocument/hover", "file.uri" -> params.getTextDocument.getUri) {
      underlying.hover(params)
    }

  override def documentHighlights(params: TextDocumentPositionParams): CompletableFuture[util.List[DocumentHighlight]] =
    traceRequest("textDocument/documentHighlight", uriAttr(params)) {
      underlying.documentHighlights(params)
    }

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]] =
    traceRequest("textDocument/documentSymbol", "file.uri" -> params.getTextDocument.getUri) {
      underlying.documentSymbol(params)
    }

  override def formatting(params: DocumentFormattingParams): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/formatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.formatting(params)
    }

  override def onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/onTypeFormatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.onTypeFormatting(params)
    }

  override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/rangeFormatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.rangeFormatting(params)
    }

  override def prepareRename(params: TextDocumentPositionParams): CompletableFuture[Range] =
    traceRequest("textDocument/prepareRename", uriAttr(params)) {
      underlying.prepareRename(params)
    }

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    traceRequest("textDocument/rename", "file.uri" -> params.getTextDocument.getUri) {
      underlying.rename(params)
    }

  override def references(params: ReferenceParams): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/references", "file.uri" -> params.getTextDocument.getUri) {
      underlying.references(params)
    }

  override def prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture[util.List[CallHierarchyItem]] =
    traceRequest("textDocument/prepareCallHierarchy", "file.uri" -> params.getTextDocument.getUri) {
      underlying.prepareCallHierarchy(params)
    }

  override def callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    traceRequest("callHierarchy/incomingCalls") {
      underlying.callHierarchyIncomingCalls(params)
    }

  override def callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    traceRequest("callHierarchy/outgoingCalls") {
      underlying.callHierarchyOutgoingCalls(params)
    }

  override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("textDocument/prepareTypeHierarchy", "file.uri" -> params.getTextDocument.getUri) {
      underlying.prepareTypeHierarchy(params)
    }

  override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("typeHierarchy/supertypes") {
      underlying.typeHierarchySupertypes(params)
    }

  override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("typeHierarchy/subtypes") {
      underlying.typeHierarchySubtypes(params)
    }

  override def completion(params: CompletionParams): CompletableFuture[CompletionList] =
    traceRequest("textDocument/completion", "file.uri" -> params.getTextDocument.getUri) {
      underlying.completion(params)
    }

  override def completionItemResolve(item: CompletionItem): CompletableFuture[CompletionItem] =
    traceRequest("completionItem/resolve") {
      underlying.completionItemResolve(item)
    }

  override def signatureHelp(params: TextDocumentPositionParams): CompletableFuture[SignatureHelp] =
    traceRequest("textDocument/signatureHelp", uriAttr(params)) {
      underlying.signatureHelp(params)
    }

  override def codeAction(params: CodeActionParams): CompletableFuture[util.List[CodeAction]] =
    traceRequest("textDocument/codeAction", "file.uri" -> params.getTextDocument.getUri) {
      underlying.codeAction(params)
    }

  override def codeActionResolve(params: CodeAction): CompletableFuture[CodeAction] =
    traceRequest("codeAction/resolve") {
      underlying.codeActionResolve(params)
    }

  override def codeLens(params: CodeLensParams): CompletableFuture[util.List[CodeLens]] =
    traceRequest("textDocument/codeLens", "file.uri" -> params.getTextDocument.getUri) {
      underlying.codeLens(params)
    }

  override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[util.List[FoldingRange]] =
    traceRequest("textDocument/foldingRange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.foldingRange(params)
    }

  override def selectionRange(params: SelectionRangeParams): CompletableFuture[util.List[SelectionRange]] =
    traceRequest("textDocument/selectionRange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.selectionRange(params)
    }

  override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
    traceRequest("textDocument/semanticTokens/full", "file.uri" -> params.getTextDocument.getUri) {
      underlying.semanticTokensFull(params)
    }

  override def inlayHints(params: InlayHintParams): CompletableFuture[util.List[InlayHint]] =
    traceRequest("textDocument/inlayHint", "file.uri" -> params.getTextDocument.getUri) {
      underlying.inlayHints(params)
    }

  override def inlayHintResolve(inlayHint: InlayHint): CompletableFuture[InlayHint] =
    traceRequest("inlayHint/resolve") {
      underlying.inlayHintResolve(inlayHint)
    }

  // ---- WorkspaceService ----

  override def workspaceSymbol(params: WorkspaceSymbolParams): CompletableFuture[util.List[SymbolInformation]] =
    traceRequest("workspace/symbol", "query" -> params.getQuery) {
      underlying.workspaceSymbol(params)
    }

  override def executeCommand(params: ExecuteCommandParams): CompletableFuture[Object] =
    traceRequest("workspace/executeCommand", "command" -> params.getCommand) {
      underlying.executeCommand(params)
    }

  override def willRenameFiles(params: RenameFilesParams): CompletableFuture[WorkspaceEdit] =
    traceRequest("workspace/willRenameFiles") {
      underlying.willRenameFiles(params)
    }

  override def didChangeConfiguration(params: DidChangeConfigurationParams): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeConfiguration") {
      underlying.didChangeConfiguration(params)
    }

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeWatchedFiles") {
      underlying.didChangeWatchedFiles(params)
    }

  override def didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeWorkspaceFolders") {
      underlying.didChangeWorkspaceFolders(params)
    }

  // ---- MetalsService ----

  override def treeViewChildren(params: TreeViewChildrenParams): CompletableFuture[MetalsTreeViewChildrenResult] =
    traceRequest("metals/treeViewChildren", "viewId" -> params.viewId) {
      underlying.treeViewChildren(params)
    }

  override def treeViewParent(params: TreeViewParentParams): CompletableFuture[TreeViewParentResult] =
    traceRequest("metals/treeViewParent", "viewId" -> params.viewId) {
      underlying.treeViewParent(params)
    }

  override def treeViewReveal(params: TextDocumentPositionParams): CompletableFuture[TreeViewNodeRevealResult] =
    traceRequest("metals/treeViewReveal", uriAttr(params)) {
      underlying.treeViewReveal(params)
    }

  override def findTextInDependencyJars(params: FindTextInDependencyJarsRequest): CompletableFuture[util.List[Location]] =
    traceRequest("metals/findTextInDependencyJars", "query" -> params.query) {
      underlying.findTextInDependencyJars(params)
    }

  override def doctorVisibilityDidChange(params: DoctorVisibilityDidChangeParams): CompletableFuture[Unit] =
    traceRequest("metals/doctorVisibilityDidChange") {
      underlying.doctorVisibilityDidChange(params)
    }

  override def treeViewVisibilityDidChange(params: TreeViewVisibilityDidChangeParams): CompletableFuture[Unit] =
    traceRequest("metals/treeViewVisibilityDidChange", "viewId" -> params.viewId) {
      underlying.treeViewVisibilityDidChange(params)
    }

  override def treeViewNodeCollapseDidChange(params: TreeViewNodeCollapseDidChangeParams): CompletableFuture[Unit] =
    traceRequest("metals/treeViewNodeCollapseDidChange", "viewId" -> params.viewId) {
      underlying.treeViewNodeCollapseDidChange(params)
    }

  override def didFocus(params: AnyRef): CompletableFuture[DidFocusResult.Value] =
    traceRequest("metals/didFocusTextDocument", "file.uri" -> params.toString) {
      underlying.didFocus(params)
    }

  // ---- WindowService ----

  override def didCancelWorkDoneProgress(params: WorkDoneProgressCancelParams): Unit =
    underlying.didCancelWorkDoneProgress(params)
}
```

- [ ] **Step 2: Format with scalafmt**

```bash
./bin/scalafmt
```

- [ ] **Step 3: Commit**

```bash
git add metals/src/main/scala/scala/meta/internal/metals/TracedScalaLspService.scala
git commit -m "feat: add TracedScalaLspService decorator"
```

---

### Task 4: Wire decorator into MetalsLanguageServer

**Files:**
- Modify: `metals/src/main/scala/scala/meta/metals/MetalsLanguageServer.scala`

- [ ] **Step 1: Change the wiring**

In `MetalsLanguageServer.scala`, change line 196 from:
```scala
        metalsService.underlying = service
```
to:
```scala
        metalsService.underlying = new TracedScalaLspService(service)
```

- [ ] **Step 2: Add import**

At the top of `MetalsLanguageServer.scala`, add:
```scala
import scala.meta.internal.metals.TracedScalaLspService
```

- [ ] **Step 3: Commit**

```bash
git add metals/src/main/scala/scala/meta/metals/MetalsLanguageServer.scala
git commit -m "feat: wire TracedScalaLspService into request chain"
```

---

### Task 5: Add BSP spans in BuildServerConnection

**Files:**
- Modify: `metals/src/main/scala/scala/meta/internal/metals/BuildServerConnection.scala`

- [ ] **Step 1: Add span creation in register()**

In `BuildServerConnection.scala`, modify the `register[T]` method (line 535+) to create a span around the BSP call. Add after line 540:

```scala
  import scala.meta.internal.metals.MetalsTracer

  private def register[T: ClassTag](
      action: MetalsBuildServer => CompletableFuture[T],
      onFail: => Option[(T, String)] = None,
      timeout: Option[Timeout] = None,
      restartByDefault: Boolean = false,
  ): CompletableFuture[T] = {
    val spanName = "bsp/" + implicitly[ClassTag[T]].runtimeClass.getSimpleName
    val span = MetalsTracer.startSpan(spanName, "span.kind" -> "CLIENT")
```

Then wrap the return value to close the span. The method currently ends with:
```scala
    CancelTokens.future { token =>
      token.onCancel().asScala.onComplete {
        case Success(java.lang.Boolean.TRUE) => localCancelable.cancel()
        case _ =>
      }
      actionFuture
    }
```

Change to:
```scala
    val result = CancelTokens.future { token =>
      token.onCancel().asScala.onComplete {
        case Success(java.lang.Boolean.TRUE) => localCancelable.cancel()
        case _ =>
      }
      actionFuture
    }
    result.whenComplete { (_, error) =>
      if (error != null) MetalsTracer.recordException(span, error)
      MetalsTracer.endSpan(span)
    }
    result
```

Note: Remove the `import scala.meta.internal.metals.MetalsTracer` from line 540 area and use the fully qualified import. Actually, add the import at the top of the file.

Wait, actually the register method is inside the class. Let me check the correct approach. The import should be at the top of the file. And the spanName variable should be used in `startSpan`.

- [ ] **Step 2: Add import at top of BuildServerConnection.scala**

Find the existing imports section and add:
```scala
import scala.meta.internal.metals.MetalsTracer
```

- [ ] **Step 3: Commit**

```bash
git add metals/src/main/scala/scala/meta/internal/metals/BuildServerConnection.scala
git commit -m "feat: add BSP call spans in BuildServerConnection.register"
```

---

### Task 6: Compile and verify

- [ ] **Step 1: Compile the metals module**

Use MCP `compile` tool on the metals module.

- [ ] **Step 2: Verify existing tests still pass**

Use MCP `test` tool to run unit tests.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: compilation/test fixes from OTEL tracing"
```
