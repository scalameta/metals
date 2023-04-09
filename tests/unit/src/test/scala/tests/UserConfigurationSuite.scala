package tests

import java.util.Properties

import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.JavaFormatConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.Location

class UserConfigurationSuite extends BaseSuite {
  def check(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty,
  )(
      fn: Either[List[String], UserConfiguration] => Unit
  )(implicit loc: Location): Unit = {
    test(name) {
      val json = UserConfiguration.parse(original)
      val jprops = new Properties()
      // java11 ambiguous .putAll via Properties/Hashtable, use .put
      props.foreach { case (k, v) => jprops.put(k, v) }
      val obtained =
        UserConfiguration.fromJson(json, ClientConfiguration.default, jprops)
      fn(obtained)
    }
  }

  def checkOK(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty,
  )(fn: UserConfiguration => Unit)(implicit loc: Location): Unit = {
    check(name, original, props) {
      case Left(errs) =>
        fail(s"Expected success. Obtained error: $errs")
      case Right(obtained) =>
        fn(obtained)
    }
  }
  def checkError(
      name: String,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    check(name, original) {
      case Right(ok) =>
        fail(s"Expected error. Obtained successful value $ok")
      case Left(errs) =>
        val obtained = errs.mkString("\n")
        assertNoDiff(obtained, expected)
    }
  }

  checkOK(
    "basic",
    """
      |{
      | "java-home": "home",
      | "compile-on-save": "current-project",
      | "sbt-script": "script"
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtScript == Some("script"))
  }

  checkOK(
    "empty-object",
    "{}",
  ) { obtained =>
    assert(obtained.javaHome.isEmpty)
    assert(obtained.sbtScript.isEmpty)
    assert(
      obtained.scalafmtConfigPath ==
        UserConfiguration.default.scalafmtConfigPath
    )
    assert(
      obtained.scalafixConfigPath ==
        UserConfiguration.default.scalafixConfigPath
    )
  }

  checkOK(
    "empty-string",
    "{'java-home':''}",
  ) { obtained => assert(obtained.javaHome.isEmpty) }

  checkOK(
    "sys-props",
    """
      |{
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "home",
      "metals.sbt-script" -> "script",
    ),
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtScript == Some("script"))
  }

  // we support camel case to not break existing clients using `javaHome`.
  checkOK(
    "camel",
    """
      |{
      |  "javaHome": "home"
      |}
    """.stripMargin,
  ) { obtained => assert(obtained.javaHome == Some("home")) }

  checkOK(
    "conflict",
    """
      |{
      |  "java-home": "a"
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "b"
    ),
  ) { obtained => assert(obtained.javaHome == Some("b")) }

  checkOK(
    "empty",
    """
      |{
      |  "java-home": ""
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "b"
    ),
  ) { obtained => assert(obtained.javaHome == Some("b")) }

  checkOK(
    "empty-prop",
    """
      |{
      |  "java-home": "a"
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> ""
    ),
  ) { obtained => assert(obtained.javaHome == Some("a")) }

  checkError(
    "type-mismatch",
    """
      |{
      | "sbt-script": []
      |}
    """.stripMargin,
    """
      |json error: key 'sbt-script' should have value of type string but obtained []
    """.stripMargin,
  )

  checkError(
    "symbol-prefixes",
    """
      |{
      | "symbol-prefixes": {
      |   "a.b": "c"
      | }
      |}
    """.stripMargin,
    "invalid SemanticDB symbol 'a.b': missing descriptor, " +
      "did you mean `a.b/` or `a.b.`? " +
      "(to learn the syntax see https://scalameta.org/docs/semanticdb/specification.html#symbol-1)",
  )

  checkOK(
    "strip-margin false",
    """
      |{
      | "enable-strip-margin-on-type-formatting": false
      |}
    """.stripMargin,
  ) { ok => assert(ok.enableStripMarginOnTypeFormatting == false) }

  checkOK(
    "java format setting",
    """
      |{
      | "javaFormat": {
      |  "eclipseConfigPath": "path",
      |  "eclipseProfile": "profile"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.javaFormatConfig == Some(
        JavaFormatConfig(AbsolutePath("path"), Some("profile"))
      )
    )
  }
  checkOK(
    "java format no setting",
    """
      |{
      |}
    """.stripMargin,
  ) { obtained =>
    assert(obtained.javaFormatConfig == None)
  }
  checkOK(
    "java format no profile setting",
    """
      |{
      | "javaFormat": {
      |  "eclipseConfigPath": "path"
      | }
      |}
    """.stripMargin,
  ) { obtained =>
    assert(
      obtained.javaFormatConfig == Some(
        JavaFormatConfig(AbsolutePath("path"), None)
      )
    )
  }
}
