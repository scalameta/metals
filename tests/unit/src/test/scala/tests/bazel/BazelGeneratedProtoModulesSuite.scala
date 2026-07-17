package tests.bazel

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.importer.BazelGeneratedProtoModules
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode

import tests.BaseSuite

class BazelGeneratedProtoModulesSuite extends BaseSuite {

  private val reporter =
    "//src/java/io/bazel/rulesscala/scalac/reporter:reporter"
  private val scalaDepsJavaProto =
    "//src/java/io/bazel/rulesscala/scalac/reporter:scala_deps_java_proto"
  private val scalaDepsProto =
    "//src/java/io/bazel/rulesscala/scalac/reporter:scala_deps_proto"
  private val diagnosticsJavaProto =
    "//src/protobuf/io/bazel/rules_scala:diagnostics_java_proto"
  private val diagnosticsProto =
    "//src/protobuf/io/bazel/rules_scala:diagnostics_proto"

  private val ruleClasses = Map(
    reporter -> "java_library",
    scalaDepsJavaProto -> "java_proto_library",
    diagnosticsJavaProto -> "java_proto_library",
  )

  // `ruleInput` edges, the consumer lists both java_proto_library deps,
  // each java_proto_library lists its wrapped in-repo proto_library.
  private val deps = Map(
    reporter -> List(scalaDepsJavaProto, diagnosticsJavaProto),
    scalaDepsJavaProto -> List(scalaDepsProto),
    diagnosticsJavaProto -> List(diagnosticsProto),
  )

  /** A `bazel-bin` tree with the given generated jar files materialized. */
  private def bazelBinWith(relativeJars: String*): Path = {
    val bin = Files.createTempDirectory("bazel-bin")
    for (relative <- relativeJars) {
      val jar = relative.split('/').foldLeft(bin)(_.resolve(_))
      Files.createDirectories(jar.getParent)
      Files.createFile(jar)
    }
    bin
  }

  test("discovers-generated-proto-jars-with-sources") {
    val bin = bazelBinWith(
      "src/java/io/bazel/rulesscala/scalac/reporter/libscala_deps_proto-speed.jar",
      "src/java/io/bazel/rulesscala/scalac/reporter/scala_deps_proto-speed-src.jar",
      "src/protobuf/io/bazel/rules_scala/libdiagnostics_proto-speed.jar",
      "src/protobuf/io/bazel/rules_scala/diagnostics_proto-speed-src.jar",
    )
    val result = BazelGeneratedProtoModules.discover(
      targets = List(reporter),
      ruleClassesByTarget = ruleClasses,
      depsByTarget = deps,
      bazelBin = Some(bin),
    )

    assertEquals(
      result.modules.map(_.id),
      Seq(
        "bazel-proto:src/java/io/bazel/rulesscala/scalac/reporter:scala_deps_proto-speed",
        "bazel-proto:src/protobuf/io/bazel/rules_scala:diagnostics_proto-speed",
      ),
    )
    // The consuming java_library gets BOTH generated proto modules.
    assertEquals(
      result.moduleIdsByTarget.get(reporter).map(_.size),
      Some(2),
    )
    val diagnostics = result.modules
      .find(_.id.contains("diagnostics_proto"))
      .get
    assert(
      diagnostics.jar.endsWith("libdiagnostics_proto-speed.jar"),
      s"jar should be the generated class jar, was ${diagnostics.jar}",
    )
    assert(
      diagnostics.sources.endsWith("diagnostics_proto-speed-src.jar"),
      s"sources should be the -src.jar sibling, was ${diagnostics.sources}",
    )
  }

  test("probes-mode-and-skips-jar-without-src") {
    // `lite` mode, and no `-src.jar` present for the diagnostics jar.
    val bin = bazelBinWith(
      "src/java/io/bazel/rulesscala/scalac/reporter/libscala_deps_proto-lite.jar",
      "src/java/io/bazel/rulesscala/scalac/reporter/scala_deps_proto-lite-src.jar",
      "src/protobuf/io/bazel/rules_scala/libdiagnostics_proto-lite.jar",
    )
    val result = BazelGeneratedProtoModules.discover(
      targets = List(reporter),
      ruleClassesByTarget = ruleClasses,
      depsByTarget = deps,
      bazelBin = Some(bin),
    )
    assertEquals(
      result.modules.map(_.id).toSet,
      Set(
        "bazel-proto:src/java/io/bazel/rulesscala/scalac/reporter:scala_deps_proto-lite",
        "bazel-proto:src/protobuf/io/bazel/rules_scala:diagnostics_proto-lite",
      ),
    )
    val diagnostics = result.modules
      .find(_.id.contains("diagnostics_proto"))
      .get
    assertEquals(
      Option(diagnostics.sources),
      None,
      "no -src.jar present -> no sources",
    )
  }

  test("discovers-root-package-proto-library") {
    // A proto_library in the workspace root package (`//:x`) generates its jar
    // directly under `bazel-bin`.
    val bin = bazelBinWith(
      "libroot_proto-speed.jar",
      "root_proto-speed-src.jar",
    )
    val result = BazelGeneratedProtoModules.discover(
      targets = List("//:consumer"),
      ruleClassesByTarget = Map("//:root_java_proto" -> "java_proto_library"),
      depsByTarget = Map(
        "//:consumer" -> List("//:root_java_proto"),
        "//:root_java_proto" -> List("//:root_proto"),
      ),
      bazelBin = Some(bin),
    )
    assertEquals(
      result.modules.map(_.id),
      Seq("bazel-proto::root_proto-speed"),
    )
    val module = result.modules.head
    assert(module.jar.endsWith("libroot_proto-speed.jar"))
    assert(module.sources.endsWith("root_proto-speed-src.jar"))
    assertEquals(
      result.moduleIdsByTarget.get("//:consumer"),
      Some(Set("bazel-proto::root_proto-speed")),
    )
  }

  test("skips-java-proto-library-whose-jar-is-not-on-disk") {
    val bin = bazelBinWith()
    val result = BazelGeneratedProtoModules.discover(
      targets = List(reporter),
      ruleClassesByTarget = ruleClasses,
      depsByTarget = deps,
      bazelBin = Some(bin),
    )
    assertEquals(result.modules, Seq.empty)
    assertEquals(result.moduleIdsByTarget, Map.empty[String, Set[String]])
  }

  test("skips-external-proto-library") {
    // Deliberately skipped.
    val bin = bazelBinWith(
      "external/bazel_worker_api+/libworker_protocol_proto-speed.jar"
    )
    val result = BazelGeneratedProtoModules.discover(
      targets = List("//pkg:consumer"),
      ruleClassesByTarget = Map("//pkg:wp_java_proto" -> "java_proto_library"),
      depsByTarget = Map(
        "//pkg:consumer" -> List("//pkg:wp_java_proto"),
        "//pkg:wp_java_proto" -> List(
          "@bazel_worker_api//:worker_protocol_proto"
        ),
      ),
      bazelBin = Some(bin),
    )
    assertEquals(result.modules, Seq.empty)
  }

  test("no-bazel-bin-yields-nothing") {
    val result = BazelGeneratedProtoModules.discover(
      targets = List(reporter),
      ruleClassesByTarget = ruleClasses,
      depsByTarget = deps,
      bazelBin = None,
    )
    assertEquals(result, BazelGeneratedProtoModules.Result.empty)
  }

  test("from-discovery-attaches-proto-modules-to-consuming-namespace") {
    val bin = bazelBinWith(
      "src/protobuf/io/bazel/rules_scala/libdiagnostics_proto-speed.jar",
      "src/protobuf/io/bazel/rules_scala/diagnostics_proto-speed-src.jar",
    )
    val generated = BazelGeneratedProtoModules.discover(
      targets = List(reporter),
      ruleClassesByTarget = Map(
        reporter -> "java_library",
        diagnosticsJavaProto -> "java_proto_library",
      ),
      depsByTarget = Map(
        reporter -> List(diagnosticsJavaProto),
        diagnosticsJavaProto -> List(diagnosticsProto),
      ),
      bazelBin = Some(bin),
    )
    val protoId =
      "bazel-proto:src/protobuf/io/bazel/rules_scala:diagnostics_proto-speed"
    val reporterPkg = "//src/java/io/bazel/rulesscala/scalac/reporter"

    val build = MbtBuildFixture.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List(reporter),
      srcsByTarget = Map(reporter -> List(s"$reporterPkg:ProtoReporter.java")),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = Map(reporter -> Some("2.12.21")),
      inactiveSources = Map.empty,
      versionSpecificSourceLabels = Set.empty,
      generatedProtoModules = generated,
    )
    val namespaces = build.getNamespaces.asScala
    // The reporter package namespace carries the generated proto jar even though
    // the target is Java-only and the proto_library is out of scope.
    assertEquals(
      namespaces(reporterPkg).getDependencyModuleIds.asScala.toSet,
      Set(protoId),
    )
    val module = build
      .getDependencyModules()
      .asScala
      .find(_.id == protoId)
      .get
    assert(module.jar.endsWith("libdiagnostics_proto-speed.jar"))
    assert(module.sources.endsWith("diagnostics_proto-speed-src.jar"))
  }

}
