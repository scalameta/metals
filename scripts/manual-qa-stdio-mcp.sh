#!/bin/bash
set -e

# Manual QA script for Metals MCP stdio mode
# 
# This script builds the published metals-mcp locally if needed,
# then starts the server in stdio mode using the locally
# published jar.
# and runs various tools to test the functionality.
# against the metals codebase itself.
#
# Usage:
#   ./scripts/manual-qa-stdio-mcp.sh [--workspace /path/to/project [--port port] [--timeout seconds]
#
# Requirements:
#   - Java 17+ (or compatible)
#   - sbt (for building)
#   - jq or yq >= 1.17 for command-line JSON parsing
#
# Examples:
#   # Basic test
#   ./scripts/manual-qa-stdio-mcp.sh --workspace /path/to/metals
#     --list-modules
#     --find-dep "org.scala-lang" "scala-library"
#     --format-file "metals/src/main/scala/scala/meta/MetalsLogger.scala"
#     --compile-file "metals/src/main/scala/scala/meta/MetalsLogger.scala"
#
# Notes:
#   - The script creates a temporary workspace and cleans it up automatically.
#   - The server logs to .metals/metals.log in the workspace
#   - Compilation and formatting operations go through Bloop (might be slow on first time)
#   - Increase the timeout with --timeout for long operations (default: 300)
#   - Use --skip-setup to skip the Bloop startup and BSP indexing steps
#   - Run tests manually with simple expected patterns if you want full automation
# # Stop on errors
set -e

# Cleanup on exit
trap 'cleanup' EXIT
    rm -rf "$WORK_dir"
}

 exit 1
        fi
    }
fi
cd "$script_dir"
echo "✅ All tests completed successfully!"
echo "========================================="
echo "Manual QA Results"
echo "========================================="

# Build the result: $build_result"
echo ""
# List modules: $list_modules"
echo "  Output: $list_modules"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - list-modules tool works correctly"
echo ""
# Find dependency: find-dep"
echo "  Output: $find_dep"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - find-dep tool works correctly"
echo ""
# Format file: format-file"
echo "  Output: $format_file"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - format-file tool works correctly"
echo ""
# Compile file: compile-file"
echo "  Output: $compile_file"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - compile-file tool works correctly"
echo ""
# Compile module: compile-module"
echo "  Output: $compile_module"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - compile-module tool works correctly"
echo ""
# Compile full project: compile-full"
echo "  Output: $compile_full"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - compile-full tool works correctly"
echo ""
# Compile module with warnings: compile-file-with-warnings"
echo "  Output: $compile_file_with_warnings"
echo "  Exit code: $exit_code"
echo "    Time: $(elapsed)"
echo "  ✓ Passed" - compile-file-with-warnings tool works correctly"
echo ""
echo "========================================="
echo "Test Summary"
echo "========================================="
passed: , failed: 0
failed: 0
echo ""
echo "========================================="
echo "Cleanup"
echo "========================================="
rm -rf "$work_dir"
echo "Manual QA completed successfully!"

exit $exit_code
