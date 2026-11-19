package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelQuery
import scala.meta.internal.metals.mbt.importer.BazelTargetsXmlDump

import munit.FunSuite

class BazelSelectOptsSuite extends FunSuite {

  test("unflattened-query-skips-empty-targets") {
    assertEquals(
      BazelQuery.unflattenedRuleAttrsQuery(
        Nil,
        List("scalacopts", "javacopts"),
      ),
      None,
    )
  }

  test("unflattened-query-quotes-targets-without-deps") {
    val query = BazelQuery
      .unflattenedRuleAttrsQuery(
        List("//pkg:plain", "//pkg:name+value"),
        List("scalacopts", "javacopts"),
      )
      .getOrElse(fail("expected non-empty query"))

    assertEquals(query.query, """set(//pkg:plain "//pkg:name+value")""")
    assertEquals(
      query.outputMode,
      BazelQuery.OutputMode.StreamedJsonproto,
    )
    assert(query.extraArgs.contains("--proto:flatten_selects=false"))
    assert(
      query.extraArgs.contains("--proto:output_rule_attrs=scalacopts,javacopts")
    )
  }

  test("parse-plain-concat-select-and-missing-default") {
    val jsonproto = List(
      """{"type":"RULE","rule":{"name":"//pkg:plain","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"STRING_LIST","stringListValue":["-g","-parameters"],"explicitlySpecified":true}]}}""",
      """{"type":"RULE","rule":{"name":"//pkg:with_select","ruleClass":"scala_library","attribute":[""" +
        """{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[""" +
        """{"entries":[{"label":"//conditions:default","stringListValue":["-g"],"isDefaultValue":false}]},""" +
        """{"entries":[{"label":"//pkg:use_ecj","stringListValue":["-err:all","-failOnWarning"],"isDefaultValue":false},{"label":"//conditions:default","stringListValue":["-Xlint:all"],"isDefaultValue":false}]},""" +
        """{"entries":[{"label":"//conditions:default","stringListValue":["-Werror"],"isDefaultValue":false}]}]}},""" +
        """{"name":"scalacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[""" +
        """{"entries":[{"label":"//pkg:strict","stringListValue":["-Xfatal-warnings"]},{"label":"//conditions:default","stringListValue":["-deprecation"]}]}]}}""" +
        """]}}""",
      """{"type":"RULE","rule":{"name":"//pkg:no_default","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[{"entries":[{"label":"//pkg:use_ecj","stringListValue":["-warn:-unused"]}]}]}}]}}""",
      """{"type":"RULE","rule":{"name":"//pkg:none_default","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[{"entries":[{"label":"//pkg:use_ecj","stringListValue":["-err:all"]},{"label":"//conditions:default","isDefaultValue":true}]}]}}]}}""",
    ).mkString("\n")

    val parsed = BazelQuery.parseUnflattenedStringLists(
      jsonproto,
      Set("scalacopts", "javacopts"),
    )

    val xmlDump = new BazelTargetsXmlDump(
      """<?xml version="1.1" encoding="UTF-8" standalone="no"?>
        |<query version="2">
        |  <rule class="java_library" name="//pkg:no_default">
        |    <list name="javacopts">
        |      <string value="-warn:-unused"/>
        |    </list>
        |  </rule>
        |  <rule class="java_library" name="//pkg:xml_only">
        |    <list name="javacopts">
        |      <string value="-Xlint:unchecked"/>
        |    </list>
        |  </rule>
        |</query>""".stripMargin
    )

    val mergedJavac =
      xmlDump
        .getStrings("javacopts") ++ parsed.getOrElse("javacopts", Map.empty)
    val mergedScalac =
      xmlDump.getStrings("scalacopts") ++ parsed.getOrElse(
        "scalacopts",
        Map.empty,
      )

    assertEquals(mergedJavac("//pkg:plain"), List("-g", "-parameters"))
    assertEquals(
      mergedJavac("//pkg:with_select"),
      List("-g", "-Xlint:all", "-Werror"),
    )
    assertEquals(mergedJavac("//pkg:no_default"), Nil)
    assertEquals(mergedJavac("//pkg:none_default"), Nil)
    assertEquals(mergedJavac("//pkg:xml_only"), List("-Xlint:unchecked"))
    assertEquals(mergedScalac("//pkg:with_select"), List("-deprecation"))
  }

  test("parse-canonical-main-repo-default-condition-labels") {
    val jsonproto = List(
      """{"type":"RULE","rule":{"name":"@//pkg:single_at","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[{"entries":[{"label":"@//conditions:default","stringListValue":["-g"]}]},{"entries":[{"label":"@//pkg:use_ecj","stringListValue":["-err:all"]},{"label":"@//conditions:default","stringListValue":["-Xlint:all"]}]}]}}]}}""",
      """{"type":"RULE","rule":{"name":"@@//pkg:double_at","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[{"entries":[{"label":"@@//conditions:default","stringListValue":["-g"]}]},{"entries":[{"label":"@@//pkg:use_ecj","stringListValue":["-err:all"]},{"label":"@@//conditions:default","stringListValue":["-Werror"]}]}]}}]}}""",
      """{"type":"RULE","rule":{"name":"@@//pkg:external_cond","ruleClass":"java_library","attribute":[{"name":"javacopts","type":"SELECTOR_LIST","selectorList":{"type":"STRING_LIST","elements":[{"entries":[{"label":"@@other_repo+//conditions:default","stringListValue":["-err:all"]}]}]}}]}}""",
    ).mkString("\n")

    val parsed = BazelQuery.parseUnflattenedStringLists(
      jsonproto,
      Set("javacopts"),
    )

    assertEquals(
      parsed("javacopts")("@//pkg:single_at"),
      List("-g", "-Xlint:all"),
    )
    assertEquals(
      parsed("javacopts")("@@//pkg:double_at"),
      List("-g", "-Werror"),
    )
    assertEquals(
      parsed("javacopts")("@@//pkg:external_cond"),
      Nil,
    )
  }

  test("parse-omitted-attribute-preserves-xml-options") {
    val jsonproto = List(
      """{"type":"RULE","rule":{"name":"//pkg:omitted_scalac","ruleClass":"scala_library","attribute":[{"name":"javacopts","type":"STRING_LIST","stringListValue":["-g"],"explicitlySpecified":true}]}}""",
      """{"type":"RULE","rule":{"name":"//pkg:empty_scalac","ruleClass":"scala_library","attribute":[{"name":"scalacopts","type":"STRING_LIST","explicitlySpecified":true}]}}""",
    ).mkString("\n")

    val parsed = BazelQuery.parseUnflattenedStringLists(
      jsonproto,
      Set("scalacopts", "javacopts"),
    )

    val xmlDump = new BazelTargetsXmlDump(
      """<?xml version="1.1" encoding="UTF-8" standalone="no"?>
        |<query version="2">
        |  <rule class="scala_library" name="//pkg:omitted_scalac">
        |    <list name="scalacopts">
        |      <string value="-Xsource:3"/>
        |    </list>
        |    <list name="javacopts">
        |      <string value="-warn:-unused"/>
        |    </list>
        |  </rule>
        |  <rule class="scala_library" name="//pkg:empty_scalac">
        |    <list name="scalacopts">
        |      <string value="-Xfatal-warnings"/>
        |    </list>
        |  </rule>
        |</query>""".stripMargin
    )

    val mergedScalac =
      xmlDump.getStrings("scalacopts") ++ parsed.getOrElse(
        "scalacopts",
        Map.empty,
      )
    val mergedJavac =
      xmlDump.getStrings("javacopts") ++ parsed.getOrElse(
        "javacopts",
        Map.empty,
      )

    assert(!parsed("scalacopts").contains("//pkg:omitted_scalac"))
    assertEquals(mergedScalac("//pkg:omitted_scalac"), List("-Xsource:3"))
    assertEquals(mergedJavac("//pkg:omitted_scalac"), List("-g"))
    assertEquals(mergedScalac("//pkg:empty_scalac"), Nil)
  }

  test("parse-empty-output-preserves-xml-fallback") {
    assertEquals(
      BazelQuery.parseUnflattenedStringLists(
        "",
        Set("scalacopts", "javacopts"),
      ),
      Map.empty[String, Map[String, List[String]]],
    )
  }
}
