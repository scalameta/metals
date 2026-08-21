package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelLabels
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode
import scala.meta.internal.metals.mbt.importer.BazelTargetsXmlDump

class BazelMbtBuildSupportSuite extends tests.BaseSuite {

  test("xml-dump-extracts-test-class-string-attribute") {
    val dump = new BazelTargetsXmlDump(sampleQueryXml)
    assertEquals(
      dump.getStrings("test_class"),
      Map(
        "//test:BarTest" -> List("test.BarTest"),
        "//test:FooTest" -> List("test.FooTest"),
        "//test:FooSpec" -> Nil,
      ),
    )
    assertEquals(
      dump.ruleClassesByTarget,
      Map(
        "//test:BarTest" -> "java_test",
        "//test:FooTest" -> "java_test",
        "//test:FooSpec" -> "scala_test",
      ),
    )
  }

  test("from-discovery-records-explicit-and-inferred-test-classes") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//test:FooTest", "//test:BarTest", "//test:FooSpec"),
      srcsByTarget = Map(
        "//test:FooTest" -> List("//test:FooTest.java"),
        "//test:BarTest" -> List("//test:BarTest.java"),
        "//test:FooSpec" -> List("//test:FooSpec.scala"),
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map(
        "//test:FooTest" -> List("junit:junit:4.13.2"),
        "//test:BarTest" -> List("junit:junit:4.13.2"),
        "//test:FooSpec" -> List("org.scalatest:scalatest_2.13:3.2.19"),
      ),
      runTargets = Set("//test:FooTest", "//test:BarTest", "//test:FooSpec"),
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersion = Some("2.13.16"),
      testTargets = Set("//test:FooTest", "//test:BarTest", "//test:FooSpec"),
    )

    val namespace = build.getNamespaces.get("//test")
    assert(namespace != null, "expected //test namespace")
    val testClasses = namespace.getTestClasses
      .map(tc => (tc.className, tc.configuration, tc.framework))
    assertEquals(
      testClasses,
      Seq(
        ("test.BarTest", "//test:BarTest", "JUnit"),
        ("test.FooSpec", "//test:FooSpec", "ScalaTest"),
        ("test.FooTest", "//test:FooTest", "JUnit"),
      ),
    )
  }

  test("from-discovery-workspace-mode-collects-all-test-classes") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.Workspace,
      targetLabels = List("//test:FooTest", "//app:main"),
      srcsByTarget = Map(
        "//test:FooTest" -> List("//test:FooTest.java"),
        "//app:main" -> List("//app:Main.java"),
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map(
        "//test:FooTest" -> List("junit:junit:4.13.2")
      ),
      runTargets = Set("//test:FooTest", "//app:main"),
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersion = None,
      testTargets = Set("//test:FooTest"),
    )

    val namespace = build.getNamespaces.get("bazel-workspace")
    assert(namespace != null, "expected bazel-workspace namespace")
    assertEquals(
      namespace.getTestClasses.map(tc => (tc.className, tc.configuration)),
      Seq(("test.FooTest", "//test:FooTest")),
    )
  }

  test("does-not-infer-class-names-when-a-test-has-multiple-sources") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//test:all"),
      srcsByTarget = Map(
        "//test:all" -> List("//test:FooSpec.scala", "//test:Helper.scala")
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set("//test:all"),
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersion = None,
      testTargets = Set("//test:all"),
    )

    val namespace = build.getNamespaces.get("//test")
    assert(namespace != null, "expected //test namespace")
    assertEquals(namespace.getTestClasses, Nil)
  }

  test("infers-class-name-stripping-maven-source-root") {
    assertEquals(
      inferredClassNames(
        "//modules/play/identityconsistencyservice-it:IdentityConsistencyServiceSystemSpec",
        "//modules/play/identityconsistencyservice-it:src/test/scala/system/backend/IdentityConsistencyServiceSystemSpec.scala",
      ),
      Seq("system.backend.IdentityConsistencyServiceSystemSpec"),
    )
  }

  test("infers-class-name-stripping-java-and-cross-version-source-roots") {
    assertEquals(
      inferredClassNames(
        "//module:FooTest",
        "//module:src/test/java/com/example/FooTest.java",
      ),
      Seq("com.example.FooTest"),
    )
    assertEquals(
      inferredClassNames(
        "//module:BarSpec",
        "//module:src/test/scala-2.13/com/example/BarSpec.scala",
      ),
      Seq("com.example.BarSpec"),
    )
  }

  test("infers-default-package-class-name-after-stripping-source-root") {
    assertEquals(
      inferredClassNames(
        "//foo:FooTest",
        "//foo:src/test/scala/FooTest.scala",
      ),
      Seq("FooTest"),
    )
  }

  private def inferredClassNames(
      target: String,
      src: String,
  ): Seq[String] = {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List(target),
      srcsByTarget = Map(target -> List(src)),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set(target),
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersion = None,
      testTargets = Set(target),
    )
    val namespaceKey = BazelLabels.packageKey(target).getOrElse(target)
    Option(build.getNamespaces.get(namespaceKey))
      .getOrElse(fail(s"missing namespace for $target"))
      .getTestClasses
      .map(_.className)
  }

  private val sampleQueryXml: String =
    """|<?xml version="1.0" encoding="UTF-8" standalone="no"?>
       |<query version="2">
       |  <rule class="java_test" location="/workspace/test/BUILD:1:1" name="//test:FooTest">
       |    <string name="name" value="FooTest"/>
       |    <list name="srcs">
       |      <label value="//test:FooTest.java"/>
       |    </list>
       |    <string name="test_class" value="test.FooTest"/>
       |  </rule>
       |  <rule class="java_test" location="/workspace/test/BUILD:8:1" name="//test:BarTest">
       |    <string name="name" value="BarTest"/>
       |    <list name="srcs">
       |      <label value="//test:BarTest.java"/>
       |    </list>
       |    <string name="test_class" value="test.BarTest"/>
       |  </rule>
       |  <rule class="scala_test" location="/workspace/test/BUILD:15:1" name="//test:FooSpec">
       |    <string name="name" value="FooSpec"/>
       |    <list name="srcs">
       |      <label value="//test:FooSpec.scala"/>
       |    </list>
       |  </rule>
       |</query>
       |""".stripMargin
}
