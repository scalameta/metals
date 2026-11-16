package tests.mbt

import java.util.IdentityHashMap

import scala.meta.internal.metals.mbt.importer.BazelTargetsXmlDump

import munit.FunSuite

class BazelTargetsXmlDumpSuite extends FunSuite {

  test("external-deps-transitive-and-skips-double-at-and-sources") {
    val xml =
      """|<query version="2">
         |  <rule class="java_library" name="//app:app">
         |    <list name="srcs"><label value="//app:App.java"/></list>
         |    <list name="deps"><label value="//mid:mid"/></list>
         |    <rule-input name="//app:App.java"/>
         |    <rule-input name="//mid:mid"/>
         |    <rule-input name="@@bazel_tools//tools/jdk:current_java_runtime"/>
         |  </rule>
         |  <rule class="java_library" name="//mid:mid">
         |    <list name="deps">
         |      <label value="//core:core"/>
         |      <label value="@maven//:org_slf4j_slf4j_api"/>
         |    </list>
         |    <rule-input name="//core:core"/>
         |    <rule-input name="@maven//:org_slf4j_slf4j_api"/>
         |  </rule>
         |  <rule class="java_library" name="//core:core">
         |    <list name="srcs"><label value="//core:Core.java"/></list>
         |    <list name="deps"><label value="@maven//:com_google_guava_guava"/></list>
         |    <rule-input name="//core:Core.java"/>
         |    <rule-input name="@maven//:com_google_guava_guava"/>
         |  </rule>
         |  <rule class="jvm_import" name="@maven//:com_google_guava_guava">
         |    <rule-input name="@maven//:com_google_j2objc_j2objc_annotations"/>
         |  </rule>
         |  <rule class="java_runtime" name="@@bazel_tools//tools/jdk:current_java_runtime"/>
         |  <source-file name="//app:App.java"/>
         |  <source-file name="//core:Core.java"/>
         |</query>""".stripMargin

    val dump = new BazelTargetsXmlDump(xml)
    val targets = List("//app:app", "//mid:mid", "//core:core")
    val externalDeps = dump.externalDepsByTarget(targets)

    assertEquals(
      externalDeps("//core:core").toSet,
      Set(
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_j2objc_j2objc_annotations",
      ),
    )
    assertEquals(
      externalDeps("//mid:mid").toSet,
      Set(
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_j2objc_j2objc_annotations",
        "@maven//:org_slf4j_slf4j_api",
      ),
    )
    assertEquals(externalDeps("//app:app"), externalDeps("//mid:mid"))
    assert(externalDeps("//app:app") eq externalDeps("//mid:mid"))
    assertEquals(
      externalDeps.map { case (k, v) => k -> v.toSet },
      dump.externalDepsByTarget(dump.reachableLabels(targets)).map {
        case (k, v) => k -> v.toSet
      },
    )
  }

  test("reachable-labels-where-filters-import-targets") {
    val xml =
      """|<query version="2">
         |  <rule class="scala_library" name="//app:app">
         |    <rule-input name="//mid:mid"/>
         |  </rule>
         |  <rule class="scala_library" name="//mid:mid">
         |    <rule-input name="//third_party:mylib"/>
         |  </rule>
         |  <rule class="scala_import" name="//third_party:mylib">
         |    <list name="jars"><label value="//third_party:mylib.jar"/></list>
         |    <rule-input name="//third_party:myother"/>
         |  </rule>
         |  <rule class="java_import" name="//third_party:myother">
         |    <list name="jars"><label value="//third_party:myother.jar"/></list>
         |  </rule>
         |</query>""".stripMargin

    val dump = new BazelTargetsXmlDump(xml)
    val targets = List(
      "//app:app", "//mid:mid", "//third_party:mylib", "//third_party:myother",
      "//missing:target",
    )
    val importSet = dump.jarLabelsByImportTarget.keySet
    val reachableImports =
      dump.reachableLabelsWhere(targets)(importSet.contains)

    assertEquals(
      reachableImports("//app:app").toSet,
      Set("//third_party:mylib", "//third_party:myother"),
    )
    assertEquals(
      reachableImports("//mid:mid").toSet,
      Set("//third_party:mylib", "//third_party:myother"),
    )
    assertEquals(
      reachableImports("//third_party:mylib"),
      List("//third_party:myother"),
    )
    assertEquals(reachableImports("//third_party:myother"), Nil)
    assertEquals(reachableImports("//missing:target"), Nil)
    assertEquals(
      dump.reachableLabelsWhere(targets)(_ => false),
      targets.map(_ -> Nil).toMap,
    )
    assertEquals(
      dump.reachableLabelsWhere(Nil)(_ => true),
      Map.empty[String, List[String]],
    )
  }

  test("handles-cycles-and-self-loops") {
    val xml =
      """|<query version="2">
         |  <rule class="java_library" name="//cycle:self">
         |    <rule-input name="//cycle:self"/>
         |    <rule-input name="@maven//:self_dep"/>
         |  </rule>
         |  <rule class="java_library" name="//cycle:a">
         |    <rule-input name="//cycle:b"/>
         |    <rule-input name="@maven//:dep_a"/>
         |  </rule>
         |  <rule class="java_library" name="//cycle:b">
         |    <rule-input name="//cycle:c"/>
         |    <rule-input name="@maven//:dep_b"/>
         |  </rule>
         |  <rule class="java_library" name="//cycle:c">
         |    <rule-input name="//cycle:a"/>
         |    <rule-input name="//leaf:d"/>
         |  </rule>
         |  <rule class="java_library" name="//leaf:d">
         |    <rule-input name="@maven//:dep_d"/>
         |  </rule>
         |</query>""".stripMargin

    val dump = new BazelTargetsXmlDump(xml)
    val targets = List(
      "//cycle:self", "//cycle:a", "//cycle:b", "//cycle:c", "//leaf:d",
    )

    val allReachable = dump.reachableLabels(targets)
    assertEquals(allReachable("//cycle:self"), List("@maven//:self_dep"))
    assertEquals(
      allReachable("//cycle:a").toSet,
      Set(
        "//cycle:b", "//cycle:c", "//leaf:d", "@maven//:dep_a",
        "@maven//:dep_b", "@maven//:dep_d",
      ),
    )
    assertEquals(
      allReachable("//cycle:b").toSet,
      Set(
        "//cycle:a", "//cycle:c", "//leaf:d", "@maven//:dep_a",
        "@maven//:dep_b", "@maven//:dep_d",
      ),
    )

    val extDeps = dump.externalDepsByTarget(targets)
    assertEquals(extDeps("//cycle:self"), List("@maven//:self_dep"))
    val expectedCycleExt =
      Set("@maven//:dep_a", "@maven//:dep_b", "@maven//:dep_d")
    assertEquals(extDeps("//cycle:a").toSet, expectedCycleExt)
    assertEquals(extDeps("//cycle:b").toSet, expectedCycleExt)
    assertEquals(extDeps("//cycle:c").toSet, expectedCycleExt)
    assert(extDeps("//cycle:a") eq extDeps("//cycle:b"))
    assert(extDeps("//cycle:b") eq extDeps("//cycle:c"))
  }

  test("large-shared-core-and-leaves-shares-closures") {
    val coreCount = 2000
    val leafCount = 5000
    val sb = new StringBuilder(1 << 20)
    sb.append("""<query version="2">""")
    sb.append(
      """<rule class="java_import" name="//third_party:shared_import"><list name="jars"><label value="//third_party:shared.jar"/></list></rule>"""
    )
    for (i <- 0 until coreCount) {
      sb.append(s"""<rule class="java_library" name="//core:c$i">""")
      sb.append(s"""<rule-input name="//core:C$i.java"/>""")
      sb.append(
        """<rule-input name="@@bazel_tools//tools/jdk:current_java_runtime"/>"""
      )
      if (i == 0) {
        sb.append("""<rule-input name="@maven//:org_slf4j_slf4j_api"/>""")
        sb.append("""<rule-input name="//third_party:shared_import"/>""")
      } else {
        sb.append(s"""<rule-input name="//core:c${i - 1}"/>""")
      }
      if (i == coreCount / 2) {
        sb.append("""<rule-input name="@maven//:com_google_guava_guava"/>""")
      }
      sb.append("</rule>")
      sb.append(s"""<source-file name="//core:C$i.java"/>""")
    }
    val topCore = s"//core:c${coreCount - 1}"
    for (i <- 0 until leafCount) {
      sb.append(s"""<rule class="java_test" name="//leaves:l$i">""")
      sb.append(s"""<rule-input name="//leaves:L$i.java"/>""")
      sb.append(s"""<rule-input name="$topCore"/>""")
      sb.append("""<rule-input name="@maven//:junit_junit"/>""")
      sb.append("</rule>")
      sb.append(s"""<source-file name="//leaves:L$i.java"/>""")
    }
    sb.append(
      """<rule class="java_runtime" name="@@bazel_tools//tools/jdk:current_java_runtime"/>"""
    )
    sb.append("</query>")

    val dump = new BazelTargetsXmlDump(sb.toString())
    val coreTargets = (0 until coreCount).map(i => s"//core:c$i").toList
    val leafTargets = (0 until leafCount).map(i => s"//leaves:l$i").toList
    val allTargets =
      "//third_party:shared_import" :: coreTargets ::: leafTargets

    val extDeps = dump.externalDepsByTarget(allTargets)
    assertEquals(
      extDeps("//core:c0"),
      List("@maven//:org_slf4j_slf4j_api"),
    )
    assertEquals(
      extDeps(topCore).toSet,
      Set("@maven//:org_slf4j_slf4j_api", "@maven//:com_google_guava_guava"),
    )
    assertEquals(
      extDeps("//leaves:l0").toSet,
      Set(
        "@maven//:org_slf4j_slf4j_api",
        "@maven//:com_google_guava_guava",
        "@maven//:junit_junit",
      ),
    )

    val distinctListInstances =
      new IdentityHashMap[List[String], java.lang.Boolean]()
    for (deps <- extDeps.values) {
      distinctListInstances.put(deps, java.lang.Boolean.TRUE)
    }
    // Across 7,001 targets, only 4 distinct List instances exist:
    // Nil, [slf4j], [slf4j, guava], and [slf4j, guava, junit].
    assertEquals(distinctListInstances.size(), 4)
    assert(extDeps("//leaves:l0") eq extDeps(s"//leaves:l${leafCount - 1}"))

    val importTargets = dump.reachableLabelsWhere(allTargets)(
      dump.jarLabelsByImportTarget.contains
    )
    assertEquals(importTargets("//third_party:shared_import"), Nil)
    assertEquals(
      importTargets("//leaves:l0"),
      List("//third_party:shared_import"),
    )
    assert(
      importTargets("//leaves:l0") eq importTargets(
        s"//leaves:l${leafCount - 1}"
      )
    )
  }

}
