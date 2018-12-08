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
      val json = new JsonParser().parse(original).getAsJsonObject
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
      | "sbt-launcher": "launch",
      | "java-home": "home",
      | "sbt-options": "a b"
      |}
    """.stripMargin
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtLauncher == Some("launch"))
    assert(obtained.sbtOpts == List("a", "b"))
  }

  checkOK(
    "empty",
    "{}"
  ) { obtained =>
    assert(obtained.javaHome.isEmpty)
    assert(obtained.sbtLauncher.isEmpty)
    assert(obtained.sbtOpts.isEmpty)
  }

  checkOK(
    "sys-props",
    """
      |{
      |}
    """.stripMargin,
    Map(
      "metals.sbt-launcher" -> "launch",
      "metals.java-home" -> "home",
      "metals.sbt-options" -> "a b"
    )
  ) { obtained =>
    assert(obtained.javaHome == Some("home"))
    assert(obtained.sbtLauncher == Some("launch"))
    assert(obtained.sbtOpts == List("a", "b"))
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
      | "sbt-launcher": []
      |}
    """.stripMargin,
    """
      |json error: key 'sbt-launcher' should have value of type string but obtained []
    """.stripMargin
  )
}
