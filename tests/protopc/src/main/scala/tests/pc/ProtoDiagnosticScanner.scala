package tests.pc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.util.control.NonFatal

import scala.meta.internal.proto.binder.Binder
import scala.meta.internal.proto.binder.ImportResolver
import scala.meta.internal.proto.diag.ProtoError
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser

/**
 * CLI tool to scan proto files for diagnostics.
 *
 * Usage: ProtoDiagnosticScanner <directory> [--debug <file>]
 *
 * This scans all .proto files in the given directory and reports any
 * diagnostics (type resolution errors, parse errors, etc).
 *
 * With --debug <file>, only scans the specified file and prints detailed info.
 */
object ProtoDiagnosticScanner {

  case class Diagnostic(
      file: Path,
      line: Int,
      column: Int,
      message: String,
      errorType: String,
  )

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      println("Usage: ProtoDiagnosticScanner <directory> [--debug <file>]")
      sys.exit(1)
    }

    val rootDir = Paths.get(args(0))
    if (!Files.isDirectory(rootDir)) {
      println(s"Error: $rootDir is not a directory")
      sys.exit(1)
    }

    // Check for debug mode
    val debugFile = args.indexOf("--debug") match {
      case -1 => None
      case i if i + 1 < args.length => Some(Paths.get(args(i + 1)))
      case _ => None
    }

    println(s"Scanning proto files in: $rootDir")
    val startTime = System.currentTimeMillis()

    val diagnostics = debugFile match {
      case Some(file) => scanSingleFileDebug(rootDir, file)
      case None => scanDirectory(rootDir)
    }

    val endTime = System.currentTimeMillis()
    val duration = (endTime - startTime) / 1000.0

    // Group by error type
    val byErrorType = diagnostics.groupBy(_.errorType)

    println()
    println("=" * 80)
    println(s"SUMMARY: Found ${diagnostics.size} diagnostics in ${duration}s")
    println("=" * 80)

    // Print summary by error type
    byErrorType.toSeq.sortBy(-_._2.size).foreach { case (errorType, diags) =>
      println(s"\n$errorType: ${diags.size} occurrences")
      // Show first 5 examples of each type
      diags.take(5).foreach { d =>
        println(s"  ${d.file}:${d.line}:${d.column}")
        println(s"    ${d.message}")
      }
      if (diags.size > 5) {
        println(s"  ... and ${diags.size - 5} more")
      }
    }

    // Print files with most diagnostics
    val byFile = diagnostics.groupBy(_.file)
    println()
    println("=" * 80)
    println("FILES WITH MOST DIAGNOSTICS:")
    println("=" * 80)
    byFile.toSeq.sortBy(-_._2.size).take(20).foreach { case (file, diags) =>
      println(s"  ${diags.size}: $file")
    }
  }

  def scanSingleFileDebug(rootDir: Path, targetFile: Path): List[Diagnostic] = {
    val protoFiles = findProtoFiles(rootDir)
    println(s"Found ${protoFiles.size} proto files in index")

    val protoFileRegistry: java.util.function.Function[String, Path] =
      (importPath: String) => {
        val result = protoFiles.find { p =>
          val pathStr = p.toString
          pathStr.endsWith(importPath) ||
          pathStr.endsWith(importPath.replace("/", java.io.File.separator))
        }.orNull
        println(
          s"  Import lookup: '$importPath' -> ${Option(result).getOrElse("NOT FOUND")}"
        )
        result
      }

    val importResolver = new ImportResolver(protoFileRegistry)
    val diagnostics = mutable.ListBuffer[Diagnostic]()

    try {
      val content = Files.readString(targetFile)
      val source = new SourceFile(targetFile.toString, content)
      val file = Parser.parse(source)

      println(s"\nParsed file: $targetFile")
      println(
        s"Package: ${Option(file.pkg().orElse(null)).map(_.fullName()).getOrElse("<none>")}"
      )
      println(s"Imports: ${file.imports().size()}")
      file.imports().asScala.foreach { imp =>
        println(s"  - ${imp.path()}")
      }

      println(s"\nRunning binder...")
      val bindingResult =
        Binder.bindWithErrors(file, importResolver, targetFile)

      val symbolTable = bindingResult.symbolTable()
      println(s"\nSymbol table has ${symbolTable.size()} symbols:")
      symbolTable.allSymbols().asScala.take(50).foreach { sym =>
        println(s"  ${sym.fullName()}")
      }
      if (symbolTable.size() > 50) {
        println(s"  ... and ${symbolTable.size() - 50} more")
      }

      println(s"\nErrors: ${bindingResult.errors().size()}")
      for (error <- bindingResult.errors().asScala) {
        val line = source.offsetToLine(error.position())
        val column = source.offsetToColumn(error.position())
        println(s"  ${line}:${column}: ${error.message()}")
        diagnostics += Diagnostic(
          targetFile,
          line,
          column,
          error.message(),
          extractErrorType(error.message()),
        )
      }
    } catch {
      case e: Exception =>
        println(s"Error processing file: ${e.getMessage}")
        e.printStackTrace()
    }

    diagnostics.toList
  }

  def scanDirectory(rootDir: Path): List[Diagnostic] = {
    // Build an index of all proto files for import resolution
    val protoFiles = findProtoFiles(rootDir)
    println(s"Found ${protoFiles.size} proto files")

    // Create import resolver with a registry that searches the directory
    val protoFileRegistry: java.util.function.Function[String, Path] =
      (importPath: String) => {
        protoFiles.find { p =>
          val pathStr = p.toString
          pathStr.endsWith(importPath) ||
          pathStr.endsWith(importPath.replace("/", java.io.File.separator))
        }.orNull
      }

    val importResolver = new ImportResolver(protoFileRegistry)

    // Scan each file
    val diagnostics = mutable.ListBuffer[Diagnostic]()
    var scanned = 0

    protoFiles.foreach { protoFile =>
      scanned += 1
      if (scanned % 100 == 0) {
        println(s"Scanned $scanned / ${protoFiles.size} files...")
      }

      try {
        val content = Files.readString(protoFile)
        val source = new SourceFile(protoFile.toString, content)
        val file = Parser.parse(source)

        // Run binding to detect name resolution errors
        val bindingResult =
          Binder.bindWithErrors(file, importResolver, protoFile)
        for (error <- bindingResult.errors().asScala) {
          val line = source.offsetToLine(error.position())
          val column = source.offsetToColumn(error.position())
          val errorType = extractErrorType(error.message())
          diagnostics += Diagnostic(
            protoFile,
            line,
            column,
            error.message(),
            errorType,
          )
        }
      } catch {
        case e: ProtoError =>
          diagnostics += Diagnostic(
            protoFile,
            e.line(),
            e.column(),
            e.rawMessage(),
            "ParseError",
          )
        case NonFatal(e) =>
          diagnostics += Diagnostic(
            protoFile,
            0,
            0,
            s"Unexpected error: ${e.getMessage}",
            "InternalError",
          )
      }
    }

    println(s"Scanned all ${protoFiles.size} files")
    diagnostics.toList
  }

  private def findProtoFiles(rootDir: Path): List[Path] = {
    // Use ripgrep for fast file discovery
    val cmd = Seq("rg", "--files", "-g", "*.proto", rootDir.toString)
    try {
      val output = cmd.!!
      output.linesIterator
        .map(line => Paths.get(line.trim))
        .filter(p => Files.isRegularFile(p))
        .toList
    } catch {
      case NonFatal(e) =>
        println(
          s"Warning: rg failed (${e.getMessage}), falling back to Files.walk"
        )
        fallbackFindProtoFiles(rootDir)
    }
  }

  private def fallbackFindProtoFiles(rootDir: Path): List[Path] = {
    val walker = Files.walk(rootDir)
    try {
      walker
        .filter(p =>
          Files.isRegularFile(p) &&
            p.toString.endsWith(".proto") &&
            !p.toString.contains("/node_modules/") &&
            !p.toString.contains("/.git/")
        )
        .iterator()
        .asScala
        .toList
    } finally {
      walker.close()
    }
  }

  private def extractErrorType(message: String): String = {
    if (message.startsWith("Unknown type:")) {
      val typeName = message.stripPrefix("Unknown type:").trim
      // Extract the package prefix pattern
      val parts = typeName.split("\\.")
      if (parts.length > 1) {
        s"UnknownType:${parts.head}.*"
      } else {
        s"UnknownType:$typeName"
      }
    } else if (message.contains("Expected")) {
      "SyntaxError"
    } else {
      "Other"
    }
  }
}
