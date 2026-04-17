package scala.meta.internal.proto.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import scala.meta.internal.proto.binder.Binder;
import scala.meta.internal.proto.binder.ImportResolver;
import scala.meta.internal.proto.diag.ProtoError;
import scala.meta.internal.proto.diag.SourceFile;
import scala.meta.internal.proto.parse.Parser;
import scala.meta.internal.proto.tree.Proto.ProtoFile;

/**
 * CLI tool to run diagnostics on proto files and print results.
 *
 * <p>Usage: ProtoDiagnosticsCli [--semanticdb] <proto-file> [<import-root>...]
 *
 * <p>Modes: (default) - Run diagnostics and show binding errors --semanticdb - Generate and print
 * SemanticDB TextDocument
 */
public class ProtoDiagnosticsCli {

  public static void main(String[] args) {
    if (args.length == 0) {
      printUsage();
      System.exit(1);
    }

    boolean semanticdbMode = false;
    int startIdx = 0;

    if (args[0].equals("--semanticdb")) {
      semanticdbMode = true;
      startIdx = 1;
    }

    if (args.length <= startIdx) {
      printUsage();
      System.exit(1);
    }

    Path protoFile = Paths.get(args[startIdx]);
    List<Path> importRoots = new ArrayList<>();
    for (int i = startIdx + 1; i < args.length; i++) {
      importRoots.add(Paths.get(args[i]));
    }

    // Add the proto file's directory as an import root
    if (protoFile.getParent() != null) {
      importRoots.add(protoFile.getParent());
    }

    try {
      if (semanticdbMode) {
        runSemanticdb(protoFile, importRoots);
      } else {
        runDiagnostics(protoFile, importRoots);
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void printUsage() {
    System.err.println("Usage: ProtoDiagnosticsCli [--semanticdb] <proto-file> [<import-root>...]");
    System.err.println();
    System.err.println("Modes:");
    System.err.println("  (default)     - Run diagnostics and show binding errors");
    System.err.println("  --semanticdb  - Generate and print SemanticDB TextDocument");
    System.err.println();
    System.err.println("Arguments:");
    System.err.println("  <proto-file>   - Path to the proto file to check");
    System.err.println("  <import-root>  - Optional directories to search for imports");
  }

  private static void runSemanticdb(Path protoFile, List<Path> importRoots) throws IOException {
    System.out.println("Generating SemanticDB for: " + protoFile);
    System.out.println("Import roots: " + importRoots);
    System.out.println();

    // Read the file
    String content = Files.readString(protoFile);
    SourceFile source = new SourceFile(protoFile.toString(), content);

    // Create import resolver
    Function<String, Path> registry = createRegistry(importRoots);
    ImportResolver importResolver = new ImportResolver(registry);

    try {
      // Parse
      ProtoFile file = Parser.parse(source);

      // Bind with import resolution to get symbol table
      Binder.BindingResult result = Binder.bindWithErrors(file, importResolver, protoFile);

      // Generate SemanticDB
      System.out.println("=== SemanticDB TextDocument ===");
      System.out.println("URI: " + protoFile.toUri());
      System.out.println("Language: PROTOBUF");
      System.out.println();

      // Print symbols with occurrences
      String pkg = file.pkg().map(p -> p.fullName() + "/").orElse("");

      System.out.println("=== Symbol Occurrences ===");
      printOccurrences(file, pkg, content);

      System.out.println();
      System.out.println("=== Formatted Output (SemanticdbPrinter style) ===");
      printFormattedSemanticdb(file, pkg, content);

      // Also show any binding errors
      if (!result.errors().isEmpty()) {
        System.out.println();
        System.out.println("=== Binding Errors ===");
        for (Binder.BindingError error : result.errors()) {
          int line = source.offsetToLine(error.position());
          int col = source.offsetToColumn(error.position());
          System.out.println(
              "Error at line " + (line + 1) + ", col " + (col + 1) + ": " + error.message());
          System.out.println("  TypeRef: " + error.typeRef().fullName());
        }
      }

    } catch (ProtoError e) {
      System.out.println(
          "✗ Parse error at "
              + protoFile.getFileName()
              + ":"
              + (e.line() + 1)
              + ":"
              + (e.column() + 1));
      System.out.println("  Message: " + e.rawMessage());
    }
  }

  private static void printOccurrences(ProtoFile file, String pkg, String content) {
    // Print all occurrences
    for (Object decl : file.declarations()) {
      if (decl instanceof scala.meta.internal.proto.tree.Proto.MessageDecl) {
        scala.meta.internal.proto.tree.Proto.MessageDecl msg =
            (scala.meta.internal.proto.tree.Proto.MessageDecl) decl;
        printMessageOccurrences(msg, pkg, content);
      } else if (decl instanceof scala.meta.internal.proto.tree.Proto.EnumDecl) {
        scala.meta.internal.proto.tree.Proto.EnumDecl en =
            (scala.meta.internal.proto.tree.Proto.EnumDecl) decl;
        printEnumOccurrences(en, pkg, content);
      } else if (decl instanceof scala.meta.internal.proto.tree.Proto.ServiceDecl) {
        scala.meta.internal.proto.tree.Proto.ServiceDecl svc =
            (scala.meta.internal.proto.tree.Proto.ServiceDecl) decl;
        printServiceOccurrences(svc, pkg, content);
      }
    }
  }

  private static void printMessageOccurrences(
      scala.meta.internal.proto.tree.Proto.MessageDecl msg, String ownerSymbol, String content) {
    String symbol = ownerSymbol + msg.name().value() + "#";
    int line = offsetToLine(content, msg.name().position());
    int col = offsetToColumn(content, msg.name().position());
    System.out.println("  DEFINITION " + symbol + " at " + (line + 1) + ":" + (col + 1));

    // Fields
    for (Object f : msg.fields()) {
      scala.meta.internal.proto.tree.Proto.FieldDecl field =
          (scala.meta.internal.proto.tree.Proto.FieldDecl) f;
      String fieldSymbol = symbol + field.name().value() + ".";
      int fLine = offsetToLine(content, field.name().position());
      int fCol = offsetToColumn(content, field.name().position());
      System.out.println("  DEFINITION " + fieldSymbol + " at " + (fLine + 1) + ":" + (fCol + 1));

      // Type reference
      if (!field.type().isScalar()) {
        String typeName = field.type().fullName();
        int tLine = offsetToLine(content, field.type().position());
        int tCol = offsetToColumn(content, field.type().position());
        System.out.println("  REFERENCE " + typeName + "# at " + (tLine + 1) + ":" + (tCol + 1));
      }
    }

    // Nested messages
    for (Object n : msg.nestedMessages()) {
      printMessageOccurrences(
          (scala.meta.internal.proto.tree.Proto.MessageDecl) n, symbol, content);
    }

    // Nested enums
    for (Object n : msg.nestedEnums()) {
      printEnumOccurrences((scala.meta.internal.proto.tree.Proto.EnumDecl) n, symbol, content);
    }
  }

  private static void printEnumOccurrences(
      scala.meta.internal.proto.tree.Proto.EnumDecl en, String ownerSymbol, String content) {
    String symbol = ownerSymbol + en.name().value() + "#";
    int line = offsetToLine(content, en.name().position());
    int col = offsetToColumn(content, en.name().position());
    System.out.println("  DEFINITION " + symbol + " at " + (line + 1) + ":" + (col + 1));

    // Values
    for (Object v : en.values()) {
      scala.meta.internal.proto.tree.Proto.EnumValueDecl value =
          (scala.meta.internal.proto.tree.Proto.EnumValueDecl) v;
      String valueSymbol = symbol + value.name().value() + ".";
      int vLine = offsetToLine(content, value.name().position());
      int vCol = offsetToColumn(content, value.name().position());
      System.out.println("  DEFINITION " + valueSymbol + " at " + (vLine + 1) + ":" + (vCol + 1));
    }
  }

  private static void printServiceOccurrences(
      scala.meta.internal.proto.tree.Proto.ServiceDecl svc, String ownerSymbol, String content) {
    String symbol = ownerSymbol + svc.name().value() + "#";
    int line = offsetToLine(content, svc.name().position());
    int col = offsetToColumn(content, svc.name().position());
    System.out.println("  DEFINITION " + symbol + " at " + (line + 1) + ":" + (col + 1));

    // RPCs
    for (Object r : svc.rpcs()) {
      scala.meta.internal.proto.tree.Proto.RpcDecl rpc =
          (scala.meta.internal.proto.tree.Proto.RpcDecl) r;
      String rpcSymbol = symbol + rpc.name().value() + "().";
      int rLine = offsetToLine(content, rpc.name().position());
      int rCol = offsetToColumn(content, rpc.name().position());
      System.out.println("  DEFINITION " + rpcSymbol + " at " + (rLine + 1) + ":" + (rCol + 1));

      // Input/output types
      if (!rpc.inputType().isScalar()) {
        String typeName = rpc.inputType().fullName();
        int tLine = offsetToLine(content, rpc.inputType().position());
        int tCol = offsetToColumn(content, rpc.inputType().position());
        System.out.println("  REFERENCE " + typeName + "# at " + (tLine + 1) + ":" + (tCol + 1));
      }
      if (!rpc.outputType().isScalar()) {
        String typeName = rpc.outputType().fullName();
        int tLine = offsetToLine(content, rpc.outputType().position());
        int tCol = offsetToColumn(content, rpc.outputType().position());
        System.out.println("  REFERENCE " + typeName + "# at " + (tLine + 1) + ":" + (tCol + 1));
      }
    }
  }

  private static void printFormattedSemanticdb(ProtoFile file, String pkg, String content) {
    // Print the content with occurrence annotations like SemanticdbPrinter does
    String[] lines = content.split("\n", -1);
    List<Occurrence> occurrences = collectAllOccurrences(file, pkg, content);

    // Sort by line and column
    occurrences.sort(
        (a, b) -> {
          if (a.line != b.line) return Integer.compare(a.line, b.line);
          return Integer.compare(a.col, b.col);
        });

    int occIdx = 0;
    for (int lineNum = 0; lineNum < lines.length; lineNum++) {
      String line = lines[lineNum];
      if (!line.trim().isEmpty()) {
        System.out.print("   ");
      }
      System.out.println(line);

      // Print occurrences for this line
      while (occIdx < occurrences.size() && occurrences.get(occIdx).line == lineNum) {
        Occurrence occ = occurrences.get(occIdx);
        String indent = " ".repeat(occ.col + 3); // +3 for the "   " prefix
        String carets = "^".repeat(Math.max(1, occ.length));
        System.out.println(
            "// " + indent.substring(3) + carets + " " + occ.role.toLowerCase() + " " + occ.symbol);
        occIdx++;
      }
    }
  }

  private static List<Occurrence> collectAllOccurrences(
      ProtoFile file, String pkg, String content) {
    List<Occurrence> result = new ArrayList<>();
    for (Object decl : file.declarations()) {
      if (decl instanceof scala.meta.internal.proto.tree.Proto.MessageDecl) {
        collectMessageOccurrencesIntoList(
            (scala.meta.internal.proto.tree.Proto.MessageDecl) decl, pkg, content, result);
      } else if (decl instanceof scala.meta.internal.proto.tree.Proto.EnumDecl) {
        collectEnumOccurrencesIntoList(
            (scala.meta.internal.proto.tree.Proto.EnumDecl) decl, pkg, content, result);
      } else if (decl instanceof scala.meta.internal.proto.tree.Proto.ServiceDecl) {
        collectServiceOccurrencesIntoList(
            (scala.meta.internal.proto.tree.Proto.ServiceDecl) decl, pkg, content, result);
      }
    }
    return result;
  }

  private static void collectMessageOccurrencesIntoList(
      scala.meta.internal.proto.tree.Proto.MessageDecl msg,
      String ownerSymbol,
      String content,
      List<Occurrence> result) {
    String symbol = ownerSymbol + msg.name().value() + "#";
    result.add(
        new Occurrence(
            offsetToLine(content, msg.name().position()),
            offsetToColumn(content, msg.name().position()),
            msg.name().value().length(),
            "DEFINITION",
            symbol));

    // Fields
    for (Object f : msg.fields()) {
      scala.meta.internal.proto.tree.Proto.FieldDecl field =
          (scala.meta.internal.proto.tree.Proto.FieldDecl) f;
      result.add(
          new Occurrence(
              offsetToLine(content, field.name().position()),
              offsetToColumn(content, field.name().position()),
              field.name().value().length(),
              "DEFINITION",
              symbol + field.name().value() + "."));

      if (!field.type().isScalar()) {
        result.add(
            new Occurrence(
                offsetToLine(content, field.type().position()),
                offsetToColumn(content, field.type().position()),
                field.type().fullName().length(),
                "REFERENCE",
                field.type().fullName() + "#"));
      }
    }

    // Nested
    for (Object n : msg.nestedMessages()) {
      collectMessageOccurrencesIntoList(
          (scala.meta.internal.proto.tree.Proto.MessageDecl) n, symbol, content, result);
    }
    for (Object n : msg.nestedEnums()) {
      collectEnumOccurrencesIntoList(
          (scala.meta.internal.proto.tree.Proto.EnumDecl) n, symbol, content, result);
    }
  }

  private static void collectEnumOccurrencesIntoList(
      scala.meta.internal.proto.tree.Proto.EnumDecl en,
      String ownerSymbol,
      String content,
      List<Occurrence> result) {
    String symbol = ownerSymbol + en.name().value() + "#";
    result.add(
        new Occurrence(
            offsetToLine(content, en.name().position()),
            offsetToColumn(content, en.name().position()),
            en.name().value().length(),
            "DEFINITION",
            symbol));

    for (Object v : en.values()) {
      scala.meta.internal.proto.tree.Proto.EnumValueDecl value =
          (scala.meta.internal.proto.tree.Proto.EnumValueDecl) v;
      result.add(
          new Occurrence(
              offsetToLine(content, value.name().position()),
              offsetToColumn(content, value.name().position()),
              value.name().value().length(),
              "DEFINITION",
              symbol + value.name().value() + "."));
    }
  }

  private static void collectServiceOccurrencesIntoList(
      scala.meta.internal.proto.tree.Proto.ServiceDecl svc,
      String ownerSymbol,
      String content,
      List<Occurrence> result) {
    String symbol = ownerSymbol + svc.name().value() + "#";
    result.add(
        new Occurrence(
            offsetToLine(content, svc.name().position()),
            offsetToColumn(content, svc.name().position()),
            svc.name().value().length(),
            "DEFINITION",
            symbol));

    for (Object r : svc.rpcs()) {
      scala.meta.internal.proto.tree.Proto.RpcDecl rpc =
          (scala.meta.internal.proto.tree.Proto.RpcDecl) r;
      result.add(
          new Occurrence(
              offsetToLine(content, rpc.name().position()),
              offsetToColumn(content, rpc.name().position()),
              rpc.name().value().length(),
              "DEFINITION",
              symbol + rpc.name().value() + "()."));

      if (!rpc.inputType().isScalar()) {
        result.add(
            new Occurrence(
                offsetToLine(content, rpc.inputType().position()),
                offsetToColumn(content, rpc.inputType().position()),
                rpc.inputType().fullName().length(),
                "REFERENCE",
                rpc.inputType().fullName() + "#"));
      }
      if (!rpc.outputType().isScalar()) {
        result.add(
            new Occurrence(
                offsetToLine(content, rpc.outputType().position()),
                offsetToColumn(content, rpc.outputType().position()),
                rpc.outputType().fullName().length(),
                "REFERENCE",
                rpc.outputType().fullName() + "#"));
      }
    }
  }

  private static class Occurrence {
    final int line;
    final int col;
    final int length;
    final String role;
    final String symbol;

    Occurrence(int line, int col, int length, String role, String symbol) {
      this.line = line;
      this.col = col;
      this.length = length;
      this.role = role;
      this.symbol = symbol;
    }
  }

  private static int offsetToLine(String content, int offset) {
    int line = 0;
    for (int i = 0; i < offset && i < content.length(); i++) {
      if (content.charAt(i) == '\n') line++;
    }
    return line;
  }

  private static int offsetToColumn(String content, int offset) {
    int col = 0;
    int i = offset - 1;
    while (i >= 0 && content.charAt(i) != '\n') {
      col++;
      i--;
    }
    return col;
  }

  private static void runDiagnostics(Path protoFile, List<Path> importRoots) throws IOException {
    System.out.println("Checking: " + protoFile);
    System.out.println("Import roots: " + importRoots);
    System.out.println();

    // Read the file
    String content = Files.readString(protoFile);
    SourceFile source = new SourceFile(protoFile.toString(), content);

    // Create import resolver
    Function<String, Path> registry = createRegistry(importRoots);
    ImportResolver importResolver = new ImportResolver(registry);

    try {
      // Parse
      System.out.println("=== Parsing ===");
      ProtoFile file = Parser.parse(source);
      System.out.println("✓ Parse succeeded");
      System.out.println(
          "  Package: " + (file.pkg().isPresent() ? file.pkg().get().fullName() : "(default)"));
      System.out.println("  Imports: " + file.imports().size());
      file.imports().forEach(imp -> System.out.println("    - " + imp.path()));
      System.out.println("  Declarations: " + file.declarations().size());
      System.out.println();

      // Bind with import resolution
      System.out.println("=== Binding ===");
      Binder.BindingResult result = Binder.bindWithErrors(file, importResolver, protoFile);

      if (result.errors().isEmpty()) {
        System.out.println("✓ No errors");
      } else {
        System.out.println("✗ Found " + result.errors().size() + " error(s):");
        System.out.println();

        for (Binder.BindingError error : result.errors()) {
          int line = source.offsetToLine(error.position());
          int col = source.offsetToColumn(error.position());

          System.out.println(
              "Error at " + protoFile.getFileName() + ":" + (line + 1) + ":" + (col + 1));
          System.out.println("  Message: " + error.message());
          System.out.println("  TypeRef: " + error.typeRef().fullName());
          System.out.println("  Position: byte offset " + error.position());
          System.out.println("  Line content: " + getLine(content, line));
          System.out.println(
              "  " + " ".repeat(col) + "^".repeat(error.typeRef().fullName().length()));
          System.out.println();
        }
      }

      // Print symbol table summary
      System.out.println("=== Symbol Table ===");
      List<Object> symbols = new ArrayList<>();
      result.symbolTable().allSymbols().forEach(symbols::add);
      System.out.println("Total symbols: " + symbols.size());
      symbols.forEach(
          sym -> {
            scala.meta.internal.proto.binder.sym.Symbol symbol =
                (scala.meta.internal.proto.binder.sym.Symbol) sym;
            System.out.println(
                "  " + symbol.fullName() + " (" + symbol.getClass().getSimpleName() + ")");
          });

    } catch (ProtoError e) {
      System.out.println(
          "✗ Parse error at "
              + protoFile.getFileName()
              + ":"
              + (e.line() + 1)
              + ":"
              + (e.column() + 1));
      System.out.println("  Message: " + e.rawMessage());
      System.out.println("  Line content: " + getLine(content, e.line()));
      System.out.println("  " + " ".repeat(e.column()) + "^");
    }
  }

  private static String getLine(String content, int lineNumber) {
    String[] lines = content.split("\n", -1);
    if (lineNumber >= 0 && lineNumber < lines.length) {
      return lines[lineNumber];
    }
    return "";
  }

  private static Function<String, Path> createRegistry(List<Path> importRoots) {
    return (importPath) -> {
      System.out.println("  [Registry] Looking for: " + importPath);

      for (Path root : importRoots) {
        try {
          if (Files.isDirectory(root)) {
            // Walk the directory tree
            Path[] result = new Path[1];
            Files.walk(root)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".proto"))
                .forEach(
                    p -> {
                      if (result[0] == null
                          && (p.toString().endsWith(importPath)
                              || p.getFileName().toString().equals(importPath))) {
                        System.out.println("  [Registry] Found: " + p);
                        result[0] = p;
                      }
                    });
            if (result[0] != null) {
              return result[0];
            }
          }
        } catch (IOException e) {
          System.err.println("  [Registry] Error searching " + root + ": " + e.getMessage());
        }
      }

      System.out.println("  [Registry] Not found: " + importPath);
      return null;
    };
  }
}
