package tests

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.ExtendedScalaMainClass
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmEnvironmentItem
import ch.epfl.scala.bsp4j.ScalaMainClass

final class ExtendedScalaMainClassSuite extends BaseSuite {
  test("user-jvm-options-take-precedence") {
    val injectedUserDir = "-Duser.dir=/build/server/injected"
    val userUserDir = "-Duser.dir=/user/choice"
    val main = new ScalaMainClass(
      "a.Main",
      Nil.asJava,
      List(userUserDir, "-Xmx2G").asJava,
    )
    val env = new JvmEnvironmentItem(
      new BuildTargetIdentifier("id"),
      Nil.asJava,
      List(injectedUserDir).asJava,
      "/workspace",
      Map.empty[String, String].asJava,
    )
    val workspace = AbsolutePath(Files.createTempDirectory("metals"))
    val extended = ExtendedScalaMainClass(
      main,
      env,
      workspace.resolve("bin").resolve("java"),
      workspace,
    )

    // the JVM uses the last occurrence of a repeated option, so the
    // user-provided ones must come after those from the build server
    assertEquals(
      extended.jvmOptions.asScala.toList,
      List(injectedUserDir, userUserDir, "-Xmx2G"),
    )
    assert(
      extended.shellCommand.indexOf(injectedUserDir) <
        extended.shellCommand.indexOf(userUserDir),
      s"expected '$userUserDir' to come after '$injectedUserDir' in: ${extended.shellCommand}",
    )
  }
}
