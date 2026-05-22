# OTEL Tracing for Metals — Design Spec

**Date**: 2026-05-22
**Status**: Draft

## 1. Motivation

Add OpenTelemetry tracing to Metals for development-time debugging of request
latency, BSP call chains, and hung requests. Zero overhead when no OTEL SDK is
on the classpath.

## 2. Scope

- LSP request-level spans (definition, completion, hover, etc.)
- BSP call sub-spans (buildTarget/compile, workspace/buildTargets, etc.)
- W3C trace context propagation to BSP servers
- Opt-in via Java agent or JAVA_OPTS

**Out of scope**: SQL query spans (OTEL Java agent auto-instruments JDBC), compiler-level spans.

## 3. Build

- `io.opentelemetry:opentelemetry-api:1.58.0` (API only, ~4KB)
- No SDK — users add Java agent or SDK JAR to enable real tracing
- Default: `GlobalOpenTelemetry.get()` returns no-op → zero cost

## 4. Components

### 4.1 MetalsTracer
Thin facade over OTEL API. Single file, all OTEL imports isolated here.

### 4.2 TracedScalaLspService
Decorator implementing all four LSP service traits. Wraps every method with a
span named after the LSP method (e.g. "textDocument/definition"), sets
attributes (file.uri, file.language, span.kind), records exceptions.

Wired in `MetalsLanguageServer.initialize()` between `DelegatingScalaService`
and `WorkspaceLspService`.

### 4.3 BSP Span Injection
Spans created in `BuildServerConnection.register[T]()`, named after the
ClassTag type (e.g. "CompileResult", "SourcesResult"). No call-site changes.
Trace context injected into BSP params data field for end-to-end trace linkage.

## 5. Files

| File | Change |
|---|---|
| `project/V.scala` | Add `opentelemetry = "1.58.0"` |
| `build.sbt` | Add `opentelemetry-api` dependency |
| `.../MetalsTracer.scala` | **NEW** |
| `.../TracedScalaLspService.scala` | **NEW** |
| `.../MetalsLanguageServer.scala` | Wire decorator |
| `.../BuildServerConnection.scala` | Spans in `register()` |
