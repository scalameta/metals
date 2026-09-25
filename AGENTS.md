# AGENTS.md

This document provides instructions for AI agents working on the Metals
codebase.

## Running Tests

If Metals MCP tools are available, use them to run tests. Otherwise, use the
following command:

```bash
sbt --client SBT_PROJECT/testOnly TEST_CLASS "-- *TEST_NAME_GLOB"
```

**Note:** By convention, test names should be kebab-case with no spaces (e.g.,
`private-member` instead of `private member`).

Make sure to use the user's default shell to run the command.

### Example

```bash
sbt --client javapc/testOnly pc.CompletionIdentifierSuite "-- *private-member"
```

For more information on filtering tests in MUnit, see the
[MUnit filtering documentation](https://scalameta.org/munit/docs/filtering.html).

## Preparing for PRs

Before committing:

- ALWAYS format all changed Scala files using Metals MCP `format-file` tool or
  `./bin/scalafmt` if the former is not available.
- ALWAYS run `sbt --client scalafixAll` to fix any Scala code issues.

## Working with Scala code

- Don't use `.iterator` unless really necessary. Prefer working with higher
  order functions, like filter, map, flatmap directly on collections
- Prefer for-loops over map/flatmap, unless they fit in one line (one or maximum
  two calls)
- don't call `.toList` unless it's necessary
- ALWAYS use Metals MCP tools to compile and run tests instead of relying on
  bash commands. Only use sbt if the MCP tools are not available.
- PREFER use Metals MCP tools to format code instead of relying on bash commands
- ALWAYS use Metals MCP get-source tool to get the sources of a dependencies
- If MCP tools are not available report that to the user
- after adding a dependency to `build.sbt`, ALWAYS run the `import-build` tool
- to lookup a dependency or the latest version, use the `find-dep` tool
- to lookup the API of a class, use the `inspect` tool
- use `sbt --client` instead of `sbt` to connect to a running sbt server for
  faster execution
- NEVER use non-local returns

# Git

- always create new commits, instead of amending existing ones
