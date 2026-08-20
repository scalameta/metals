package tests.mbt

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
      testClassAttrByTarget = Map(
        "//test:FooTest" -> List("test.FooTest"),
        "//test:BarTest" -> List("test.BarTest"),
      ),
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
      testClassAttrByTarget = Map("//test:FooTest" -> List("test.FooTest")),
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
