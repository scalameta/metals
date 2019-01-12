package tests

import com.google.gson.JsonParser
import java.util.Properties
import scala.meta.internal.metals.UserConfiguration
import scala.collection.JavaConverters._

object UserConfigurationSuite extends BaseSuite {
  def check(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty
  )(fn: Either[List[String], UserConfiguration] => Unit): Unit = {
    test(name) {
      val wrapped = UserConfiguration.toWrappedJson(original)
      val json = new JsonParser().parse(wrapped).getAsJsonObject
      val jprops = new Properties()
      jprops.putAll(props.asJava)
      val obtained = UserConfiguration.fromJson(json, jprops)
      fn(obtained)
    }
  }

  def checkOK(
      name: String,
      original: String,
      props: Map[String, String] = Map.empty
  )(fn: UserConfiguration => Unit): Unit = {
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
      expected: String
  ): Unit = {
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
    """.stripMargin
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtScript == Some("script"))
  }

  checkOK(
    "empty-object",
    "{}"
  ) { obtained =>
    assert(obtained.javaHome.isEmpty)
    assert(obtained.sbtScript.isEmpty)
    assert(
      obtained.scalafmtConfigPath ==
        UserConfiguration.default.scalafmtConfigPath
    )
  }

  checkOK(
    "empty-string",
    "{'java-home':''}"
  ) { obtained =>
    assert(obtained.javaHome.isEmpty)
  }

  checkOK(
    "sys-props",
    """
      |{
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "home",
      "metals.sbt-script" -> "script"
    )
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
    """.stripMargin
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
  }

  checkOK(
    "conflict",
    """
      |{
      |  "java-home": "a"
      |}
    """.stripMargin,
    Map(
      "metals.java-home" -> "b"
    )
  ) { obtained =>
    assert(obtained.javaHome == Some("a"))
  }

  checkError(
    "type-mismatch",
    """
      |{
      | "sbt-script": []
      |}
    """.stripMargin,
    """
      |json error: key 'sbt-script' should have value of type string but obtained []
    """.stripMargin
  )

}
