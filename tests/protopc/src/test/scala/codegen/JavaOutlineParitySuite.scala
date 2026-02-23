package codegen

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.sys.process._

import scala.meta.internal.proto.codegen.java.JavaOutlineGenerator
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser

import munit.FunSuite

/**
 * Tests that verify our JavaOutlineGenerator produces code with the same
 * public API as protoc-generated code.
 *
 * The test strategy:
 * 1. Generate Java code using protoc
 * 2. Generate Java code using our outline generator
 * 3. Compile both with javac
 * 4. Compare javap -public output
 */
class JavaOutlineParitySuite extends FunSuite {

  private lazy val protocPath =
    sys.env.getOrElse("PROTOC", "protoc")

  private lazy val hasProtoc: Boolean = {
    try {
      s"$protocPath --version".! == 0
    } catch {
      case _: Exception => false
    }
  }

  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("proto-codegen-test")
    try {
      f(dir)
    } finally {
      deleteRecursively(dir.toFile)
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }

  private def writeFile(path: Path, content: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
  }

  private def generateWithProtoc(protoContent: String, tempDir: Path): Path = {
    val protoFile = tempDir.resolve("test.proto")
    val outputDir = tempDir.resolve("protoc")
    Files.createDirectories(outputDir)
    writeFile(protoFile, protoContent)

    val cmd = Seq(
      protocPath,
      s"--java_out=$outputDir",
      s"-I${tempDir}",
      protoFile.toString,
    )
    val result = cmd.!
    assertEquals(result, 0, s"protoc failed: ${cmd.mkString(" ")}")
    outputDir
  }

  private def generateWithOutlineGenerator(
      protoContent: String,
      tempDir: Path,
  ): Path = {
    val outputDir = tempDir.resolve("outline")
    Files.createDirectories(outputDir)

    val source = new SourceFile("test.proto", protoContent)
    val file = Parser.parse(source)
    val generator = new JavaOutlineGenerator()
    val outputs = generator.generate(file)

    outputs.asScala.foreach { output =>
      val path = outputDir.resolve(output.path())
      writeFile(path, output.content())
    }
    outputDir
  }

  private def compileJava(sourceDir: Path, outputDir: Path): Boolean = {
    Files.createDirectories(outputDir)

    // Find all Java files
    val javaFiles = Files
      .walk(sourceDir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".java"))
      .toList

    if (javaFiles.isEmpty) return true

    // Compile with javac
    val classpath =
      sys.props
        .getOrElse("java.class.path", "")
        .split(File.pathSeparator)
        .toList

    val cmd = Seq(
      "javac",
      "-d",
      outputDir.toString,
      "-cp",
      classpath.mkString(File.pathSeparator),
    ) ++
      javaFiles.map(_.toString)

    cmd.! == 0
  }

  private def javapPublicApi(classDir: Path, className: String): String = {
    val cmd = Seq("javap", "-public", "-cp", classDir.toString, className)
    cmd.!!
  }

  private def extractPublicMethods(javapOutput: String): Set[String] = {
    javapOutput.linesIterator
      .filter(line =>
        line.contains("public") &&
          !line.contains("Compiled from") &&
          !line.contains("{") &&
          !line.contains("}")
      )
      .map(_.trim)
      .toSet
  }

  /** Find all class files in a directory. */
  private def findClassFiles(dir: Path): List[String] = {
    Files
      .walk(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".class"))
      .map { path =>
        val relative = dir.relativize(path).toString
        // Convert path to class name: com/example/Foo.class -> com.example.Foo
        relative.replace("/", ".").replace("\\", ".").stripSuffix(".class")
      }
      .toList
      .sorted
  }

  /**
   * Compare public API of two compiled class directories.
   * Returns a list of differences (empty if APIs match).
   */
  private def comparePublicApis(
      protocClassDir: Path,
      outlineClassDir: Path,
  ): List[String] = {
    val protocClasses = findClassFiles(protocClassDir).toSet
    val outlineClasses = findClassFiles(outlineClassDir).toSet

    val differences = scala.collection.mutable.ListBuffer[String]()

    // Check for missing classes
    val missingInOutline = protocClasses -- outlineClasses
    val extraInOutline = outlineClasses -- protocClasses

    if (missingInOutline.nonEmpty) {
      differences += s"Missing classes in outline: ${missingInOutline.mkString(", ")}"
    }
    if (extraInOutline.nonEmpty) {
      differences += s"Extra classes in outline: ${extraInOutline.mkString(", ")}"
    }

    // Compare public APIs for common classes
    val commonClasses = protocClasses.intersect(outlineClasses)
    for (className <- commonClasses) {
      val protocApi = extractPublicMethods(
        javapPublicApi(protocClassDir, className)
      )
      val outlineApi = extractPublicMethods(
        javapPublicApi(outlineClassDir, className)
      )

      val missingMethods = protocApi -- outlineApi
      val extraMethods = outlineApi -- protocApi

      if (missingMethods.nonEmpty) {
        differences += s"$className missing methods: ${missingMethods.mkString("; ")}"
      }
      if (extraMethods.nonEmpty) {
        differences += s"$className extra methods: ${extraMethods.mkString("; ")}"
      }
    }

    differences.toList
  }

  /**
   * Parity tests compare our outline generator output with protoc.
   *
   * Note: Our outline generator intentionally produces a subset of the full protoc API.
   * We generate enough for IDE features (hover, go-to-definition, completions) but skip:
   * - OrBuilder interfaces (used for immutability patterns)
   * - parseFrom/parser methods (serialization)
   * - Field number constants (wire format)
   * - Bytes getters (wire format optimization)
   * - registerAllExtensions/getDescriptor (reflection)
   * - Many builder bridge methods with covariant return types
   *
   * The test below verifies both compile and prints differences for documentation.
   */
  test("simple-message-parity") {
    assume(hasProtoc, "protoc not available")

    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Person {
                  |  string name = 1;
                  |  int32 age = 2;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      // Generate with both generators
      val protocDir = generateWithProtoc(proto, tempDir)
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      // Compile protoc output
      val protocClassDir = tempDir.resolve("protoc-classes")
      val protocCompiled = compileJava(protocDir, protocClassDir)
      assert(protocCompiled, "protoc-generated code should compile")

      // Compile outline output
      val outlineClassDir = tempDir.resolve("outline-classes")
      val outlineCompiled = compileJava(outlineDir, outlineClassDir)
      assert(outlineCompiled, "outline-generated code should compile")

      // Compare public APIs - this documents the differences
      val differences = comparePublicApis(protocClassDir, outlineClassDir)
      if (differences.nonEmpty) {
        println(
          s"API differences (expected for outline generator):\n${differences.mkString("\n")}"
        )
      }
      // Don't fail - outline generator intentionally produces subset
    }
  }

  test("enum-parity") {
    assume(hasProtoc, "protoc not available")

    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |enum Status {
                  |  UNKNOWN = 0;
                  |  ACTIVE = 1;
                  |  INACTIVE = 2;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val protocDir = generateWithProtoc(proto, tempDir)
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      val protocClassDir = tempDir.resolve("protoc-classes")
      val protocCompiled = compileJava(protocDir, protocClassDir)
      assert(protocCompiled, "protoc-generated code should compile")

      val outlineClassDir = tempDir.resolve("outline-classes")
      val outlineCompiled = compileJava(outlineDir, outlineClassDir)
      assert(outlineCompiled, "outline-generated code should compile")

      val differences = comparePublicApis(protocClassDir, outlineClassDir)
      if (differences.nonEmpty) {
        println(
          s"API differences (expected for outline generator):\n${differences.mkString("\n")}"
        )
      }
    }
  }

  test("nested-message-parity") {
    assume(hasProtoc, "protoc not available")

    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Outer {
                  |  message Inner {
                  |    string value = 1;
                  |  }
                  |  Inner inner = 1;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val protocDir = generateWithProtoc(proto, tempDir)
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      val protocClassDir = tempDir.resolve("protoc-classes")
      val protocCompiled = compileJava(protocDir, protocClassDir)
      assert(protocCompiled, "protoc-generated code should compile")

      val outlineClassDir = tempDir.resolve("outline-classes")
      val outlineCompiled = compileJava(outlineDir, outlineClassDir)
      assert(outlineCompiled, "outline-generated code should compile")

      val differences = comparePublicApis(protocClassDir, outlineClassDir)
      if (differences.nonEmpty) {
        println(
          s"API differences (expected for outline generator):\n${differences.mkString("\n")}"
        )
      }
    }
  }

  test("protobuf-package-prefix") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Person {
                  |  string name = 1;
                  |}
                  |""".stripMargin

    val source = new SourceFile("test.proto", proto)
    val file = Parser.parse(source)
    val generator = new JavaOutlineGenerator("grpc_shaded.")
    val outputs = generator.generate(file).asScala.toList

    val protobufRef = "com.google.protobuf."
    val prefixedRef = "grpc_shaded." + protobufRef
    val combinedContent = outputs.map(_.content()).mkString("\n")

    assert(
      combinedContent.contains(prefixedRef),
      "expected generated output to contain prefixed protobuf references",
    )
    assert(
      !combinedContent.replace(prefixedRef, "").contains(protobufRef),
      "expected generated output to avoid unprefixed protobuf references",
    )
  }

  test("outline-code-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Person {
                  |  string name = 1;
                  |  int32 age = 2;
                  |  repeated string tags = 3;
                  |}
                  |enum Status {
                  |  UNKNOWN = 0;
                  |  ACTIVE = 1;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      // Just verify our code compiles
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(compiled, "outline-generated code should compile")
    }
  }

  test("nested-message-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Outer {
                  |  message Inner {
                  |    string value = 1;
                  |  }
                  |  Inner inner = 1;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(
        compiled,
        "outline-generated code with nested messages should compile",
      )
    }
  }

  test("map-field-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Container {
                  |  map<string, string> labels = 1;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(compiled, "outline-generated code with map fields should compile")
    }
  }

  test("oneof-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Sample {
                  |  oneof choice {
                  |    string text = 1;
                  |    int32 number = 2;
                  |  }
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(compiled, "outline-generated code with oneof should compile")
    }
  }

  test("oneof-builder-setter-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Sample {
                  |  oneof choice {
                  |    string text = 1;
                  |    int32 number = 2;
                  |  }
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)
      // Write a consumer that calls builder setters for oneof fields.
      // This regresses PLAT-154279: spurious "method not found" error when
      // setting a generated Protobuf oneof field.
      writeFile(
        outlineDir.resolve("com/example/Consumer.java"),
        """|package com.example;
           |class Consumer {
           |  void test() {
           |    TestProtos.Sample.Builder b = TestProtos.Sample.newBuilder();
           |    b.setText("hello");
           |    b.setNumber(42);
           |  }
           |}
           |""".stripMargin,
      )
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(
        compiled,
        "calling builder setters for oneof fields should compile",
      )
    }
  }

  test("multiple-files-compiles") {
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |option java_multiple_files = true;
                  |message Person {
                  |  string name = 1;
                  |  int32 age = 2;
                  |}
                  |enum Status {
                  |  UNKNOWN = 0;
                  |  ACTIVE = 1;
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      // Verify separate files were generated
      val personFile = outlineDir.resolve("com/example/Person.java")
      val statusFile = outlineDir.resolve("com/example/Status.java")
      val outerFile = outlineDir.resolve("com/example/TestProtos.java")

      assert(Files.exists(personFile), "Person.java should be generated")
      assert(Files.exists(statusFile), "Status.java should be generated")
      assert(Files.exists(outerFile), "TestProtos.java should be generated")

      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(
        compiled,
        "outline-generated code with java_multiple_files should compile",
      )
    }
  }

  test("service-implbase-toplevel-generated") {
    // PLAT-154278: When java_multiple_files=true, also generate a standalone
    // {ServiceName}ImplBase.java so that imports like
    //   import com.example.FooServiceImplBase;
    // resolve without a spurious "cannot find symbol" error.
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |option java_multiple_files = true;
                  |message Request { string query = 1; }
                  |message Response { string result = 1; }
                  |service SearchService {
                  |  rpc Search(Request) returns (Response);
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)

      val grpcFile = outlineDir.resolve("com/example/SearchServiceGrpc.java")
      val implBaseFile =
        outlineDir.resolve("com/example/SearchServiceImplBase.java")

      assert(
        Files.exists(grpcFile),
        "SearchServiceGrpc.java should be generated",
      )
      assert(
        Files.exists(implBaseFile),
        "SearchServiceImplBase.java should be generated as a top-level file",
      )

      val implBaseContent =
        new String(
          Files.readAllBytes(implBaseFile),
          java.nio.charset.StandardCharsets.UTF_8,
        )
      assert(
        implBaseContent.contains("public abstract class SearchServiceImplBase"),
        "ImplBase file should declare a top-level abstract class (not static)",
      )
    }
  }

  test("service-compiles".ignore) { // Requires grpc on classpath
    val proto = """
                  |syntax = "proto3";
                  |option java_package = "com.example";
                  |option java_outer_classname = "TestProtos";
                  |message Request {
                  |  string query = 1;
                  |}
                  |message Response {
                  |  string result = 1;
                  |}
                  |service SearchService {
                  |  rpc Search(Request) returns (Response);
                  |  rpc StreamResults(Request) returns (stream Response);
                  |}
                  |""".stripMargin

    withTempDir { tempDir =>
      val outlineDir = generateWithOutlineGenerator(proto, tempDir)
      val outlineClassDir = tempDir.resolve("outline-classes")
      val compiled = compileJava(outlineDir, outlineClassDir)
      assert(compiled, "outline-generated code with service should compile")
    }
  }
}
