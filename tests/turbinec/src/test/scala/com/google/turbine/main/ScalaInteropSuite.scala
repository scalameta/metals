package com.google.turbine.main

import com.google.common.collect.ImmutableList
import com.google.turbine.testing.TestClassPaths
import java.nio.file.Files
import java.util.jar.JarFile
import munit.FunSuite

class ScalaInteropSuite extends FunSuite {
  test("java-sees-scala-class") {
    val dir = Files.createTempDirectory("turbine-scala")
    val scala = dir.resolve("Box.scala")
    val java = dir.resolve("Use.java")
    val out = dir.resolve("out.jar")

    Files.writeString(scala, "package foo\nclass Box(val x: Int)\n")
    Files.writeString(java, "package foo; class Use { int f(Box b) { return b.x(); } }\n")

    val options = TestClassPaths.optionsWithBootclasspath()
    options.setSources(ImmutableList.of(scala.toString, java.toString))
    options.setOutput(out.toString)

    Main.compile(options.build())

    val jar = new JarFile(out.toFile)
    try {
      assert(jar.getEntry("foo/Box.class") != null)
      assert(jar.getEntry("foo/Use.class") != null)
    } finally {
      jar.close()
    }
  }

  test("header-compilation-includes-scala-deps") {
    val dir = Files.createTempDirectory("turbine-scala-header")
    val dep = dir.resolve("Base.java")
    val scala = dir.resolve("Box.scala")
    val java = dir.resolve("Use.java")
    val depJar = dir.resolve("dep.jar")
    val scalaOut = dir.resolve("scala.jar")
    val headerOut = dir.resolve("scala-header.jar")
    val downstreamOut = dir.resolve("downstream.jar")

    Files.writeString(dep, "package dep; public class Base { public int id() { return 1; } }\n")
    Files.writeString(
      scala,
      List(
        "package foo",
        "import dep.Base",
        "class Box extends Base",
        "",
      ).mkString("\n"),
    )
    Files.writeString(java, "package foo; class Use { int get(Box b) { return b.id(); } }\n")

    val depOptions = TestClassPaths.optionsWithBootclasspath()
    depOptions.setSources(ImmutableList.of(dep.toString))
    depOptions.setOutput(depJar.toString)
    Main.compile(depOptions.build())

    val scalaOptions = TestClassPaths.optionsWithBootclasspath()
    scalaOptions.setSources(ImmutableList.of(scala.toString))
    scalaOptions.setClassPath(ImmutableList.of(depJar.toString))
    scalaOptions.setOutput(scalaOut.toString)
    scalaOptions.setHeaderCompilationOutput(headerOut.toString)
    Main.compile(scalaOptions.build())

    val headerJar = new JarFile(headerOut.toFile)
    try {
      assert(headerJar.getEntry("foo/Box.class") != null)
      assert(headerJar.getEntry("META-INF/TRANSITIVE/dep/Base.turbine") != null)
    } finally {
      headerJar.close()
    }

    val javaOptions = TestClassPaths.optionsWithBootclasspath()
    javaOptions.setSources(ImmutableList.of(java.toString))
    javaOptions.setClassPath(ImmutableList.of(headerOut.toString))
    javaOptions.setOutput(downstreamOut.toString)
    Main.compile(javaOptions.build())

    val downstreamJar = new JarFile(downstreamOut.toFile)
    try {
      assert(downstreamJar.getEntry("foo/Use.class") != null)
    } finally {
      downstreamJar.close()
    }
  }

  test("java-calls-trait-impl-class") {
    val dir = Files.createTempDirectory("turbine-scala-trait")
    val scala = dir.resolve("T.scala")
    val java = dir.resolve("Use.java")
    val out = dir.resolve("out.jar")

    Files.writeString(
      scala,
      List(
        "package foo",
        "trait T {",
        "  def concrete(x: Int): Int = x",
        "}",
      ).mkString("\n"),
    )
    Files.writeString(
      java,
      List(
        "package foo;",
        "class Use implements T {",
        "  int f(int x) {",
        "    return T$class.concrete(this, x);",
        "  }",
        "}",
      ).mkString("\n"),
    )

    val options = TestClassPaths.optionsWithBootclasspath()
    options.setSources(ImmutableList.of(scala.toString, java.toString))
    options.setOutput(out.toString)

    Main.compile(options.build())

    val jar = new JarFile(out.toFile)
    try {
      assert(jar.getEntry("foo/T.class") != null)
      assert(jar.getEntry("foo/T$class.class") != null)
      assert(jar.getEntry("foo/Use.class") != null)
    } finally {
      jar.close()
    }
  }
}
