---
id: mcp
title: Model Context Protocol (MCP)
---

## Overview

Metals implements a
[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that
allows AI-powered tools to interact with your Scala project. MCP provides a
standardized way for AI assistants like Cursor, Claude Code, or other
MCP-compatible clients to access project information, compile code, run tests,
and perform intelligent code analysis.

The MCP integration helps AI agents produce more accurate suggestions by giving
them access to:

- Symbol information and documentation
- Project compilation and diagnostics
- Test execution
- Dependency management
- Code formatting and refactoring tools

## Getting Started

### Using MCP with Metals (Editor Integration)

When using Metals inside an editor like VS Code or Cursor, the MCP server runs
alongside the language server and can be enabled through settings.

1. **Enable MCP in Metals**: Set `metals.startMcpServer` to `true` in your
   editor settings.

2. **Automatic Configuration**: For common editors like Cursor or VS Code (with
   GitHub Copilot), Metals will automatically add the MCP configuration to your
   workspace when the above option is enabled. You can verify the connection in
   your AI agent settings.

3. **Manual Configuration**: For other AI tools, Metals will display a message
   with the port number when the MCP server starts. You can also find this
   information in the Metals log (`.metals/metals.log`) with the message:
   `Metals MCP server started on port: <port>`. It is also available in the
   `.metals/mcp.json` file.

The MCP server endpoint is available at `http://localhost:<port>/mcp`.

As an example of manual configuration, if you want to add Metals MCP to Claude
Code you can use the command:

```bash
claude mcp add --transport http metals "http://localhost:$(grep -oE 'localhost:[0-9]+' .metals/mcp.json | head -n1 | sed 's/.*://')/mcp"
```

Run this from the workspace root so `.metals/mcp.json` is found; the port is
read from that file.

### Using MCP Standalone

Metals also provides a standalone MCP server that can run independently of any
editor. This is useful for:

- Using AI tools that don't have built-in editor integration
- Running MCP in headless environments
- Command-line-based AI workflows

#### Installation

Install the standalone MCP server using Coursier:

```bash
cs install metals-mcp
```

#### Usage

```bash
metals-mcp --workspace /path/to/your/scala/project
```

#### Options

```
Options:
  --workspace <path>      Path to the Scala project (required)
  --port <number>         HTTP port to listen on (default: auto-assign)
  --transport <type>      Transport type: http (default) or stdio
  --client <name>         Client to generate config for: vscode, cursor,
                          claude, or others
  --<key> [value]         UserConfiguration override using kebab-case
  --help, -h              Show help message
  --version, -v           Show version information
```

#### Examples

```bash
# Start MCP server for a project
metals-mcp --workspace /path/to/project

# Start with a specific port
metals-mcp --workspace /path/to/project --port 8080

# Generate configuration for Cursor
metals-mcp --workspace /path/to/project --client cursor

# Use stdio transport (useful for tools without HTTP support)
metals-mcp --workspace /path/to/project --transport stdio

# Override user configuration
metals-mcp --workspace /path/to/project --java-home /path/to/jdk
```

## Available MCP Tools

Metals provides a comprehensive set of tools through MCP:

### Compilation Tools

| Tool             | Description                                                                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------- |
| `compile-file`   | Compile a specific Scala file. Returns errors in the file, or if none, errors in the containing module. |
| `compile-module` | Compile a chosen build target/module by name.                                                           |
| `compile-full`   | Compile the entire Scala project.                                                                       |

### Testing

| Tool   | Description                                                                                                                                            |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `test` | Run Scala test suites. Supports running specific test classes or individual test methods. Works with ScalaTest, MUnit, ZIO Test, and other frameworks. |

### Symbol Search and Inspection

| Tool                | Description                                                                                                                            |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `glob-search`       | Search for symbols by partial name matching. Find packages, classes, objects, methods, traits, and other symbols across the workspace. |
| `typed-glob-search` | Search for symbols filtered by type (package, class, object, function, method, trait).                                                 |
| `inspect`           | Inspect a Scala symbol. Returns members for packages/objects/traits, members and constructors for classes, and signatures for methods. |
| `get-docs`          | Get documentation (ScalaDoc) for a symbol.                                                                                             |
| `get-usages`        | Find all references and usages of a symbol across the project.                                                                         |
| `get-source`        | Get the source file contents for a symbol. Useful for understanding library code.                                                      |

### Build and Dependencies

| Tool           | Description                                                                                |
| -------------- | ------------------------------------------------------------------------------------------ |
| `import-build` | Re-import the build after changes (e.g., adding dependencies to build.sbt).                |
| `find-dep`     | Search for dependencies using Coursier. Complete organization, artifact name, and version. |
| `list-modules` | List all available modules (build targets) in the project.                                 |

### Code Quality

| Tool                     | Description                                                                                |
| ------------------------ | ------------------------------------------------------------------------------------------ |
| `format-file`            | Format a Scala file using the project's Scalafmt configuration.                            |
| `generate-scalafix-rule` | Generate and run a Scalafix rule on the project. Useful for automated refactorings.        |
| `run-scalafix-rule`      | Run a previously created Scalafix rule.                                                    |
| `list-scalafix-rules`    | List available Scalafix rules including the generated ones from `.metals/rules` directory. |

## Tool Usage Examples

### Compiling and Checking Errors

```
Using MCP, compile the file src/main/scala/MyApp.scala and show any errors
```

### Finding Symbols

```
Using MCP, search for all classes containing "Controller" in their name
```

### Getting Documentation

```
Using MCP, show the documentation for scala.collection.immutable.List
```

### Running Tests

```
Using MCP, run the test class com.example.MyServiceSpec
```

### Finding Dependencies

```
Using MCP, find the latest version of the cats-effect library
```

### Automated Refactoring

```
Using MCP, generate a scalafix rule that converts filter.map chains to collect
```

## Configuration

### Editor Settings

When using MCP with Metals in an editor:

| Setting                 | Description           | Default |
| ----------------------- | --------------------- | ------- |
| `metals.startMcpServer` | Enable the MCP server | `false` |

## Tips for AI Agents

When working with Metals MCP, AI agents should:

1. **After build changes**: Always call `import-build` after modifying
   `build.sbt` or other build files.

2. **Before code changes**: Use `compile-file` or `compile-full` to ensure the
   project compiles successfully.

3. **For unknown APIs**: Use `glob-search` or `inspect` to discover available
   symbols in proprietary libraries.

4. **For dependency lookup**: Use `find-dep` to find the correct artifact names
   and latest versions.

5. **After code changes**: Call `format-file` to ensure code follows project
   style guidelines.

You can add these tips to your AI agent's prompt to help it use Metals MCP
effectively. Examples of such approaches are in the
[VirtusLab Skills Repository](https://github.com/VirtusLab/scala-skill/blob/master/direct-style-scala/SKILL.md)
and within Metals itself in the
[AGENTS.md](https://github.com/scalameta/metals/blob/main/AGENTS.md) file.

## Troubleshooting

### MCP Server Not Starting

- Ensure `metals.startMcpServer` is set to `true`
- Check `.metals/metals.log` for error messages
- Verify no other process is using the MCP port

### Connection Issues

- The MCP endpoint is at `http://localhost:<port>/mcp`
- Check that your AI client is configured with the correct URL
- For editors, Metals automatically updates configurations when restarted

### Tools Not Working

- Ensure the project has been imported (`import-build`)
- Wait for indexing to complete before using search tools
- Check compilation status with `compile-full`

## Release History

MCP support was introduced in Metals v1.5.3 and has been continuously improved:

- **v1.5.3**: Initial MCP support with basic tools
- **v1.6.0**: Added `compile-module`, `find-dep`, improved error messages
- **v1.6.1**: Added `list-modules`, `format-file`, ZIO test support
- **v1.6.3**: Added Scalafix rule generation tools
- **v1.6.5**: Switched to streamable HTTP transport (`/mcp` endpoint)
- **v1.6.6**: Added standalone MCP server (`metals-mcp`)
- **v1.6.7**: Added stdio transport support, `get-source` tool
