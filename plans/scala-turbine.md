# Plan: Vendor Turbine Scala Support + Turbinec MUnit Tests + Input Classpath Compatibility

based on your answers (Scala-only suite scope, Scala 2.13 input coverage, canonicalized equality for ASM compare).

Summary

- Sync vendor/turbine with the olafurpg/scala branch in /Users/olafurpg/dev/google/turbine, bringing in Scala parsing/lowering support and
  trait impl + generic signature improvements.
- Introduce a new tests/turbinec sbt project with MUnit suites ported from Turbine’s Scala‑specific JUnit tests.
- Port the ASM-based bytecode canonicalization/diff infrastructure and add a comprehensive suite that compares Turbine‑generated Scala
  outlines against tests/input’s sbt‑compiled classpath output.

———

## 1) Sync vendor/turbine With olafurpg/scala

Goal: bring Scala support code and changes into Metals’ vendor fork.

Actions

1. Mirror Turbine main sources:
    - Copy java/com/google/turbine/** from /Users/olafurpg/dev/google/turbine to vendor/turbine/src/main/java/com/google/turbine/**.
    - Copy proto/*.proto to vendor/turbine/src/main/protobuf/.
2. Ensure new Scala packages land in vendor:
    - com/google/turbine/scalaparse/*
    - com/google/turbine/scalagen/*
3. Ensure modified core classes are updated:
    - com/google/turbine/main/Main.java (Scala source handling, header compile adjustments)
    - com/google/turbine/lower/LowerSignature.java (generic signatures, trait impls)
    - any other changed files in olafurpg/scala commits.
4. Add jspecify dependency for nullness annotations:
    - Add a version to project/V.scala, e.g. val jspecify = "org.jspecify" % "jspecify" % "1.0.0"
    - Add to turbine project libraryDependencies in build.sbt.
5. Ensure existing dependencies stay compatible (Guava/AutoValue/Protobuf already in place).

Public API/Interface Changes

- vendor/turbine now includes Scala parsing/lowering APIs:
    - com.google.turbine.scalaparse.*
    - com.google.turbine.scalagen.*
- Turbine Main accepts Scala sources and emits Scala classfile outlines.

———

## 2) Add tests/turbinec SBT Project

Goal: new Scala/MUnit test module dedicated to Turbine Scala support.

Build Configuration

1. Add project in build.sbt:
    - lazy val turbinec = project.in(file("tests/turbinec"))
    - settings(testSettings, sharedSettings, ...)
    - dependsOn(mtest, turbine)
2. Add dependencies:
    - MUnit: "org.scalameta" %% "munit" % V.munit
    - ASM: "org.ow2.asm" % "asm" % "9.9", "org.ow2.asm" % "asm-tree" % "9.9", "org.ow2.asm" % "asm-util" % "9.9"
    - Scala compiler (for ScalaConformance port): "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test
3. Reuse tests/input properties:
    - Compile / resourceGenerators += InputProperties.resourceGenerator(input, input3)
    - Compile / compile := (Compile / compile).dependsOn(input / Test / compile, input3 / Test / compile).value

Directory Layout

- tests/turbinec/src/test/scala/...
- tests/turbinec/src/test/resources/com/google/turbine/scalaparse/testdata/**

———

## 3) Port Scala JUnit Tests → MUnit

Goal: port Scala‑specific suites into Scala/MUnit.

### Suites to port

1. ScalaLexerTest → ScalaLexerSuite
2. ScalaParserTest → ScalaParserSuite
3. ScalaLowerTest → ScalaLowerSuite
4. ScalaInteropTest → ScalaInteropSuite
5. ScalaConformanceTest → ScalaConformanceSuite (rewired to use in‑process Scala compiler)

### Test Resources

- Copy these into tests/turbinec/src/test/resources/com/google/turbine/scalaparse/testdata/**:
    - lexer/*.scala, lexer/*.tokens
    - parser/*.scala, parser/*.outline

### Shared Helpers (Scala ports)

Create under tests/turbinec/src/test/scala/com/google/turbine/testing:

- TestResources (classloader resource reads)
- TestClassPaths (bootclasspath + optionsWithBootclasspath)
- AsmUtils (ASM textify)
- IntegrationTestSupport (subset of methods: sortMembers, canonicalize, dump, plus helpers)

Conformance Suite Strategy

- Replace mise/external scalac invocation with scala.tools.nsc (via scala-compiler test dependency).
- Compile Scala sources to a temp output dir in-process.
- Compile Java sources with javax.tools.JavaCompiler.
- Compare Turbine output vs scalac/javac output using ASM canonicalized equality.

Test Naming

- Use kebab‑case MUnit test names (per AGENTS.md).

———

## 4) Port ASM Compatibility Infrastructure

Goal: compare outlines without javap, using ASM canonicalization.

Ported Methods (from Turbine’s IntegrationTestSupport)

- canonicalize(Map[String, Array[Byte]])
- sortMembers(Map[String, Array[Byte]])
- dump(Map[String, Array[Byte]])
- Internal helpers: remove impl details (private/synthetic), strip debug/code, sort attributes, remove preview bits, drop local/anonymous
  classes, etc.

Notes

- Use ASM Tree API (ClassNode, MethodNode, etc).
- Preserve identical behavior to Turbine’s Java version.

———

## 5) New Comprehensive Suite: Input Classpath Compatibility

Goal: assert Turbine’s Scala outlines match sbt‑compiled classpath output for all Scala 2 sources.

Approach

1. Load metals-input.properties via local InputProperties helper (in tests/turbinec).
2. Gather Scala 2 sources:
    - Filter sourceDirectories recursively for .scala
    - Exclude scala-3 paths
3. Compile with Turbine:
    - Use TestClassPaths.optionsWithBootclasspath()
    - options.setSources(...)
    - options.setClassPath(...) from metals-input.properties
    - Write to a temp jar
4. Read Turbine output class bytes from jar.
5. For each Turbine class name, read the matching classfile from sbt classpath entries:
    - Check directory entries first (entry/<class>.class)
    - Check jars if needed
6. Compare:
    - dump(sortMembers(turbineClasses))
    - equals dump(canonicalize(scalacClasses))
    - Fail with detailed diff (ASM textified).

Test Name

- scala-outline-input-compat (single suite-level test)

———

## 6) Expand tests/input As Needed

Goal: cover uncovered Scala patterns when tests fail.

Guidelines

- Prefer minimal, isolated files in tests/input/src/main/scala.
- Add targeted Scala 2 cases for trait impls, default params, nested objects, synthetic bridges, or generics as needed.
- Keep changes small and incremental to ease debugging.

———

## Tests & Scenarios

Primary tests

1. turbinec/ScalaLexerSuite
2. turbinec/ScalaParserSuite
3. turbinec/ScalaLowerSuite
4. turbinec/ScalaInteropSuite
5. turbinec/ScalaConformanceSuite
6. turbinec/ScalaOutlineInputCompatSuite (new)

Run

- source .envrc
- coursier launch sbt -- --client turbinec/test

———

## Assumptions & Defaults

- Scala-only test scope (no non-Scala turbine tests).
- Scala 2.13 only for input coverage.
- Canonicalized equality for ASM compare (not subset).
- ScalaConformance uses in-process Scala compiler (no external mise).
