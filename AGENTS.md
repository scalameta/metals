# AGENTS.md

This document provides instructions for AI agents working on the Metals
codebase.

## Running Tests

To run tests, use the following command:

```bash
coursier launch sbt -- --client SBT_PROJECT/testOnly TEST_CLASS "-- *TEST_NAME_GLOB"
```

**Note:** By convention, test names should be kebab-case with no spaces (e.g.,
`private-member` instead of `private member`).

### Example

```bash
coursier launch sbt -- --client javapc/testOnly pc.CompletionIdentifierSuite "-- *private-member"
```

For more information on filtering tests in MUnit, see the
[MUnit filtering documentation](https://scalameta.org/munit/docs/filtering.html).
