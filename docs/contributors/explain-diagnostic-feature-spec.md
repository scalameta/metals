# Feature Specification: Explain Diagnostic with -explain Flag

## Overview

Scala 3 compiler provides an `-explain` flag that outputs more detailed explanations for certain error diagnostics. Currently, when a diagnostic has additional explanation available, the compiler appends the message:

```
longer explanation available when compiling with `-explain`
```

This feature will allow users to retrieve this detailed explanation on-demand through a code action attached to the diagnostic.

## User Story

As a Scala developer, when I encounter a diagnostic with a "longer explanation available" hint, I want to click a code action to see the detailed explanation rendered in a readable file, so that I can better understand the error without modifying my build configuration.

## Technical Approach

### 1. Detect Diagnostics with -explain Availability

**Location**: `Diagnostics.scala` or a new utility class

**Logic**:
- When processing diagnostics from the build server, check if the diagnostic message contains the pattern: `longer explanation available when compiling with \`-explain\``
- These diagnostics typically come from Scala 3 builds
- The diagnostic will have an error code that can be used to identify it

**Detection Pattern**:
```scala
val explainHintPattern = """longer explanation available when compiling with `-explain`""".r
def hasExplainHint(diagnostic: Diagnostic): Boolean = 
  diagnostic.getMessage.contains("longer explanation available when compiling with `-explain`")
```

### 2. Create a New Server Command

**Location**: `ServerCommands.scala`

Add a new command:
```scala
val ExplainDiagnostic = new ParametrizedCommand[ExplainDiagnosticParams](
  "explain-diagnostic",
  "Explain Diagnostic",
  """Run the presentation compiler with -explain flag to get detailed error explanation.
    |
    |This command compiles the file with the -explain option and renders
    |the expanded diagnostic message in a readable file.
    |""".stripMargin,
  """|Object with uri and optional diagnostic info:
     |```json
     |{
     |  "uri": "file:///path/to/file.scala",
     |  "line": 10,
     |  "character": 5
     |}
     |```
     |""".stripMargin,
)
```

**Parameters Class**:
```scala
case class ExplainDiagnosticParams(
  uri: String,
  line: Int,
  character: Int,
)
```

### 3. Create a Code Action Provider

**Location**: New file `codeactions/ExplainDiagnostic.scala`

```scala
class ExplainDiagnostic() extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    val diagnosticsWithExplain = params
      .getContext()
      .getDiagnostics()
      .asScala
      .filter(hasExplainHint)
      .filter(d => params.getRange().overlapsWith(d.getRange()))
    
    Future.successful(
      diagnosticsWithExplain.map { diagnostic =>
        val command = ServerCommands.ExplainDiagnostic.toLsp(
          ExplainDiagnosticParams(
            params.getTextDocument().getUri(),
            diagnostic.getRange().getStart().getLine(),
            diagnostic.getRange().getStart().getCharacter(),
          )
        )
        
        CodeActionBuilder.build(
          title = "Show detailed explanation (-explain)",
          kind = l.CodeActionKind.QuickFix,
          command = Some(command),
          diagnostics = List(diagnostic),
        )
      }.toSeq
    )
  }
  
  private def hasExplainHint(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getMessage)
      .exists(_.contains("longer explanation available when compiling with `-explain`"))
}
```

### 4. Add Command Handler

**Location**: `MetalsLspService.scala` / `WorkspaceLspService.scala`

Add handler for the new command:
```scala
case ServerCommands.ExplainDiagnostic(params) =>
  explainDiagnosticProvider.explain(params).asJavaObject
```

### 5. Create ExplainDiagnosticProvider

**Location**: New file `ExplainDiagnosticProvider.scala`

This provider will:
1. Take the file URI and position
2. Create a new presentation compiler instance with `-explain` flag added to options
3. Compile the file with this modified compiler
4. Capture the diagnostic output with expanded explanations
5. Write the output to a file in `.metals/` directory
6. Show the file to the user

```scala
class ExplainDiagnosticProvider(
    workspace: AbsolutePath,
    compilers: Compilers,
    buildTargets: BuildTargets,
    buffers: Buffers,
    languageClient: MetalsLanguageClient,
)(implicit ec: ExecutionContext) {

  def explain(params: ExplainDiagnosticParams): Future[Unit] = {
    val path = params.uri.toAbsolutePath
    
    for {
      content <- getFileContent(path)
      explainedDiagnostics <- compileWithExplain(path, content)
      outputPath <- writeExplainedDiagnostics(path, explainedDiagnostics, params)
      _ <- showFileToUser(outputPath)
    } yield ()
  }

  private def compileWithExplain(
      path: AbsolutePath,
      content: String,
  ): Future[String] = {
    // Get the build target for this file
    // Create a temporary PC with -explain flag
    // Compile and capture diagnostics
    ???
  }

  private def writeExplainedDiagnostics(
      sourcePath: AbsolutePath,
      diagnostics: String,
      params: ExplainDiagnosticParams,
  ): Future[AbsolutePath] = {
    val outputDir = workspace.resolve(Directories.explainedDiagnostics)
    Files.createDirectories(outputDir.toNIO)
    
    val filename = s"${sourcePath.filename}-L${params.line}-explained.md"
    val outputPath = outputDir.resolve(filename)
    
    val content = s"""# Explained Diagnostic
                     |
                     |**File**: ${sourcePath.toRelative(workspace)}
                     |**Line**: ${params.line + 1}
                     |
                     |## Detailed Explanation
                     |
                     |```
                     |$diagnostics
                     |```
                     |""".stripMargin
    
    outputPath.writeText(content)
    Future.successful(outputPath)
  }

  private def showFileToUser(path: AbsolutePath): Future[Unit] = {
    // Use ClientCommands.GotoLocation or showDocument to open the file
    val params = new l.ShowDocumentParams(path.toURI.toString)
    params.setTakeFocus(true)
    languageClient.showDocument(params).asScala.map(_ => ())
  }
}
```

### 6. Update Directories

**Location**: `Directories.scala`

Add new directory for explained diagnostics:
```scala
def explainedDiagnostics: RelativePath =
  RelativePath(".metals").resolve("explained-diagnostics")
```

### 7. Modify Compilers to Support -explain Flag

**Location**: `Compilers.scala` and/or `CompilerConfiguration.scala`

Add a method to compile a file with extra options:
```scala
def compileWithExtraOptions(
    path: AbsolutePath,
    extraOptions: List[String],
): Future[List[Diagnostic]] = {
  // Similar to didChange but with additional compiler options
  // Create a temporary presentation compiler instance with modified options
  // Return diagnostics with full messages
}
```

**Alternative Approach**: Instead of creating a new PC instance, we could:
1. Use the existing PC's ability to get diagnostics
2. Parse the diagnostic code/message to find the error ID
3. Use a companion service that has a database of explanations (if available)

### 8. Register Code Action Provider

**Location**: `CodeActionProvider.scala`

Add `ExplainDiagnostic` to the list of providers:
```scala
private val allActions: List[CodeAction] = List(
  // ... existing actions
  new ExplainDiagnostic(),
)
```

## File Output Format

The generated file in `.metals/explained-diagnostics/` will be in Markdown format:

```markdown
# Explained Diagnostic for Foo.scala

**Source File**: src/main/scala/Foo.scala
**Position**: Line 10, Column 5
**Generated at**: 2024-01-15 10:30:00

## Original Error

```
error: Type Mismatch
Found:    String
Required: Int
```

## Detailed Explanation

Type Mismatch Error:

I found:    String
But required: Int

Explanation:
The expression has type String but the context expects type Int.
This can happen when:
- You're returning a value of the wrong type from a method
- You're assigning a value to a variable with an incompatible type
- You're passing an argument of the wrong type to a function

Possible fixes:
- Convert the String to an Int using .toInt (if it represents a number)
- Change the expected type to String
- Use a different expression that produces an Int
```

## Testing Plan

1. **Unit Tests**:
   - Test detection of `-explain` hint in diagnostic messages
   - Test code action generation for qualifying diagnostics
   - Test file path generation in `.metals/` directory

2. **Integration Tests** (`tests/slow`):
   - Create a Scala 3 project with a type mismatch error
   - Verify code action appears for the diagnostic
   - Execute the command and verify file is created
   - Verify file content contains expanded explanation

3. **Manual Testing**:
   - Test in VS Code with a Scala 3 project
   - Verify the code action appears in the lightbulb menu
   - Verify clicking opens the explained file

## Implementation Steps

1. [ ] Add `ExplainDiagnosticParams` case class
2. [ ] Add `ExplainDiagnostic` command to `ServerCommands`
3. [ ] Add `explainedDiagnostics` directory to `Directories`
4. [ ] Create `ExplainDiagnostic` code action class
5. [ ] Register code action in `CodeActionProvider`
6. [ ] Create `ExplainDiagnosticProvider` class
7. [ ] Add method to `Compilers` to compile with extra options
8. [ ] Add command handler in `WorkspaceLspService`
9. [ ] Write unit tests
10. [ ] Write integration tests
11. [ ] Update documentation

## Open Questions

1. **PC Instance Management**: Should we create a new presentation compiler instance for each -explain request, or modify the existing one temporarily?
   - Creating new: Safer, no side effects, but slower
   - Modifying existing: Faster, but needs careful reset

2. **Caching**: Should we cache explained diagnostics to avoid recompilation?
   - Pro: Faster for repeated requests
   - Con: Stale results if code changes

3. **File Cleanup**: Should we automatically clean up old explained diagnostic files?
   - Could add a retention policy (e.g., delete after session ends)
   - Could add a command to clear all explained diagnostics

4. **Multiple Diagnostics**: If multiple diagnostics on the same line have -explain hints, should we:
   - Generate one file per diagnostic?
   - Generate one combined file?

## Future Enhancements

1. **Inline Rendering**: Instead of creating a file, render the explanation inline in a hover or popup
2. **Code Lens**: Add a code lens on the error line to show explanation
3. **Persistent Setting**: Allow users to always compile with -explain globally
4. **Error Database**: Build a database of common Scala 3 errors and their explanations that doesn't require recompilation

