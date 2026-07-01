package tests.bazelnative

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.builds.bazelnative.BazelNativeTargetInfoReader
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

import munit.FunSuite

class BazelNativeTargetInfoReaderSuite extends FunSuite {

  test("parses id, kind, tags") {
    val json =
      """{"id":"//src:lib","kind":"scala_library","tags":["no-ide"]}"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.id, "//src:lib")
    assertEquals(info.kind, "scala_library")
    assertEquals(info.tags, List("no-ide"))
  }

  test("parses dependencies with compile and runtime types") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "dependencies":[
        {"id":"//src:dep1","dependency_type":0},
        {"id":"//src:dep2","dependency_type":1}
      ]
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.dependencies.size, 2)
    assertEquals(info.dependencies(0).id, "//src:dep1")
    assert(info.dependencies(0).isCompile)
    assertEquals(info.dependencies(1).id, "//src:dep2")
    assert(info.dependencies(1).isRuntime)
  }

  test("parses sources with FileLocation") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "sources":[{"path":"src/Main.scala","short_path":"src/Main.scala","is_source":true}]
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.sources.size, 1)
    assertEquals(info.sources.head.path, "src/Main.scala")
    assertEquals(info.sources.head.shortPath, "src/Main.scala")
    assert(info.sources.head.isSource)
  }

  test("parses generated_sources") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "sources":[{"path":"src/Main.scala","short_path":"src/Main.scala","is_source":true}],
      "generated_sources":[{"path":"bazel-out/gen/Main.scala","short_path":"gen/Main.scala","is_source":false}]
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.sources.size, 1)
    assert(info.sources.head.isSource)
    assertEquals(info.generatedSources.size, 1)
    assertEquals(info.generatedSources.head.path, "bazel-out/gen/Main.scala")
    assert(!info.generatedSources.head.isSource)
  }

  test("parses resources") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "resources":[
        {"path":"src/resource.txt","short_path":"src/resource.txt","is_source":true}
      ]
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.resources.size, 1)
    assertEquals(info.resources.head.path, "src/resource.txt")
    assertEquals(info.resources.head.shortPath, "src/resource.txt")
  }

  test("parses env map and env_inherit") {
    val json =
      """{"id":"//src:bin","kind":"scala_binary","env":{"FOO":"bar"},"env_inherit":["PATH"]}"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.env, Map("FOO" -> "bar"))
    assertEquals(info.envInherit, List("PATH"))
  }

  test("parses executable true and false") {
    val jsonTrue =
      """{"id":"//src:bin","kind":"scala_binary","executable":true}"""
    val infoTrue = BazelNativeTargetInfoReader.parseJson(jsonTrue)
    assert(infoTrue.executable)

    val jsonFalse =
      """{"id":"//src:lib","kind":"scala_library","executable":false}"""
    val infoFalse = BazelNativeTargetInfoReader.parseJson(jsonFalse)
    assert(!infoFalse.executable)
  }

  test("parses JvmTargetInfo.jars with binary, interface, source jars") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "jvm_target_info":{
        "jars":[{
          "binary_jars":[{"path":"out/lib.jar","short_path":"lib.jar","is_source":false}],
          "interface_jars":[{"path":"out/lib-ijar.jar","short_path":"lib-ijar.jar","is_source":false}],
          "source_jars":[{"path":"out/lib-src.jar","short_path":"lib-src.jar","is_source":false}]
        }]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val jvm = info.jvmTargetInfo.get
    assertEquals(jvm.jars.size, 1)
    assertEquals(jvm.jars.head.binaryJars.size, 1)
    assertEquals(jvm.jars.head.binaryJars.head.path, "out/lib.jar")
    assertEquals(jvm.jars.head.interfaceJars.size, 1)
    assertEquals(jvm.jars.head.interfaceJars.head.path, "out/lib-ijar.jar")
    assertEquals(jvm.jars.head.sourceJars.size, 1)
    assertEquals(jvm.jars.head.sourceJars.head.path, "out/lib-src.jar")
  }

  test("parses JvmTargetInfo.generated_jars") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "jvm_target_info":{
        "generated_jars":[{
          "binary_jars":[{"path":"out/gen.jar","short_path":"gen.jar","is_source":false}],
          "interface_jars":[],
          "source_jars":[]
        }]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val jvm = info.jvmTargetInfo.get
    assertEquals(jvm.generatedJars.size, 1)
    assertEquals(jvm.generatedJars.head.binaryJars.head.path, "out/gen.jar")
  }

  test("parses JvmTargetInfo.javac_opts") {
    val json = """{
      "id":"//src:lib",
      "kind":"java_library",
      "jvm_target_info":{"javac_opts":["-Xlint","-Werror"]}
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.jvmTargetInfo.get.javacOpts, List("-Xlint", "-Werror"))
  }

  test("parses JvmTargetInfo.jvm_flags") {
    val json = """{
      "id":"//src:bin",
      "kind":"scala_binary",
      "jvm_target_info":{"jvm_flags":["-Xmx1g"]}
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.jvmTargetInfo.get.jvmFlags, List("-Xmx1g"))
  }

  test("parses JvmTargetInfo.main_class") {
    val json = """{
      "id":"//src:bin",
      "kind":"scala_binary",
      "jvm_target_info":{"main_class":"com.example.Main"}
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.jvmTargetInfo.get.mainClass, "com.example.Main")
  }

  test("parses JvmTargetInfo.args") {
    val json = """{
      "id":"//src:bin",
      "kind":"scala_binary",
      "jvm_target_info":{"args":["--verbose"]}
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.jvmTargetInfo.get.args, List("--verbose"))
  }

  test("parses JvmTargetInfo.jdeps") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "jvm_target_info":{
        "jdeps":[{"path":"out/lib.jdeps","short_path":"lib.jdeps","is_source":false}]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val jdeps = info.jvmTargetInfo.get.jdeps
    assertEquals(jdeps.size, 1)
    assertEquals(jdeps.head.path, "out/lib.jdeps")
  }

  test("parses JvmTargetInfo.transitive_compile_time_jars") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "jvm_target_info":{
        "transitive_compile_time_jars":[
          {"path":"out/dep1.jar","short_path":"dep1.jar","is_source":false},
          {"path":"out/dep2.jar","short_path":"dep2.jar","is_source":false}
        ]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val jars = info.jvmTargetInfo.get.transitiveCompileTimeJars
    assertEquals(jars.size, 2)
    assertEquals(jars.map(_.path), List("out/dep1.jar", "out/dep2.jar"))
  }

  test("parses JavaToolchainInfo") {
    val json = """{
      "id":"//src:lib",
      "kind":"java_library",
      "java_toolchain_info":{
        "source_version":"11",
        "target_version":"11",
        "java_home":{"path":"/usr/lib/jvm/java-11","short_path":"java-11","is_source":false}
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val toolchain = info.javaToolchainInfo.get
    assertEquals(toolchain.sourceVersion, "11")
    assertEquals(toolchain.targetVersion, "11")
    assertEquals(toolchain.javaHome.get.path, "/usr/lib/jvm/java-11")
    assertEquals(toolchain.javaHome.get.shortPath, "java-11")
  }

  test("parses JavaRuntimeInfo") {
    val json = """{
      "id":"//src:bin",
      "kind":"scala_binary",
      "java_runtime_info":{
        "java_home":{"path":"/usr/lib/jvm/java-17","short_path":"java-17","is_source":false}
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val runtime = info.javaRuntimeInfo.get
    assertEquals(runtime.javaHome.get.path, "/usr/lib/jvm/java-17")
  }

  test("parses ScalaTargetInfo.scalac_opts") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "scala_target_info":{"scalac_opts":["-Ysemanticdb","-Xplugin:semanticdb"]}
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(
      info.scalaTargetInfo.get.scalacOpts,
      List("-Ysemanticdb", "-Xplugin:semanticdb"),
    )
  }

  test("parses ScalaTargetInfo.compiler_classpath") {
    val json = """{
      "id":"//src:lib",
      "kind":"scala_library",
      "scala_target_info":{
        "compiler_classpath":[
          {"path":"out/scala-library.jar","short_path":"scala-library.jar","is_source":false}
        ]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val cp = info.scalaTargetInfo.get.compilerClasspath
    assertEquals(cp.size, 1)
    assertEquals(cp.head.path, "out/scala-library.jar")
  }

  test("parses ScalaTargetInfo.scalatest_classpath") {
    val json = """{
      "id":"//src:test",
      "kind":"scala_test",
      "scala_target_info":{
        "scalatest_classpath":[
          {"path":"out/scalatest.jar","short_path":"scalatest.jar","is_source":false}
        ]
      }
    }"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    val cp = info.scalaTargetInfo.get.scalatestClasspath
    assertEquals(cp.size, 1)
    assertEquals(cp.head.path, "out/scalatest.jar")
  }

  test("handles missing optional sub-messages") {
    val json = """{"id":"//src:lib","kind":"scala_library"}"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.id, "//src:lib")
    assertEquals(info.kind, "scala_library")
    assertEquals(info.jvmTargetInfo, None)
    assertEquals(info.javaToolchainInfo, None)
    assertEquals(info.javaRuntimeInfo, None)
    assertEquals(info.scalaTargetInfo, None)
  }

  test("handles unknown fields") {
    val json =
      """{"id":"//src:lib","kind":"scala_library","unknown_field":"value"}"""
    val info = BazelNativeTargetInfoReader.parseJson(json)
    assertEquals(info.id, "//src:lib")
    assertEquals(info.kind, "scala_library")
  }

  test("handles malformed JSON") {
    intercept[Exception] {
      BazelNativeTargetInfoReader.parseJson("not json")
    }
  }

  test("readFromBazelBin scans for .bsp-info.json files") {
    val tmp = AbsolutePath(Files.createTempDirectory("bazel-info-reader-test"))
    try {
      val json1 =
        """{"id":"//src:lib","kind":"scala_library"}"""
      val json2 =
        """{"id":"//src:bin","kind":"scala_binary"}"""
      Files.write(
        tmp.resolve("lib.bsp-info.json").toNIO,
        json1.getBytes(StandardCharsets.UTF_8),
      )
      val subdir = tmp.resolve("bazel-out").toNIO
      Files.createDirectories(subdir)
      Files.write(
        Paths.get(subdir.toString, "bin.bsp-info.json"),
        json2.getBytes(StandardCharsets.UTF_8),
      )

      val result = BazelNativeTargetInfoReader.readFromBazelBin(tmp.toNIO)

      assertEquals(result.size, 2)
      assertEquals(result("//src:lib").kind, "scala_library")
      assertEquals(result("//src:bin").kind, "scala_binary")
    } finally RecursivelyDelete(tmp)
  }

  test("readFromBazelBin returns empty for non-existent dir") {
    val nonExistent =
      Paths
        .get(System.getProperty("java.io.tmpdir"))
        .resolve(
          "definitely-nonexistent-" + System.nanoTime()
        )
    val result = BazelNativeTargetInfoReader.readFromBazelBin(nonExistent)
    assert(result.isEmpty)
  }
}
