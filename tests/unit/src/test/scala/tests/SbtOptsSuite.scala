package tests

import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.SbtOpts

import munit.Location

class SbtOptsSuite extends BaseSuite {
  def check(name: String, original: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(name) {
      val root = FileLayout.fromString(original)
      val obtained =
        SbtOpts.fromWorkspaceOrEnv(root).mkString("\n") ++
          JvmOpts.fromWorkspaceOrEnv(root).getOrElse(Nil).mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }
  check(
    "sbtopts",
    """
      |/.sbtopts
      |-sbt-boot /some/where/sbt/boot
      |-sbt-dir /some/where/else/sbt
      |-ivy /some/where/ivy
      |-jvm-debug 4711
      |-J-Xmx5G -J-XX:+UseG1GC
      |
    """.stripMargin,
    """
      |-Dsbt.boot.directory=/some/where/sbt/boot
      |-Dsbt.global.base=/some/where/else/sbt
      |-Dsbt.ivy.home=/some/where/ivy
      |-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=4711
      |-Xmx5G
      |-XX:+UseG1GC
    """.stripMargin,
  )

  check(
    "jvmopts",
    """
      |/.jvmopts
      |-Xmx2G
      |-Dhoodlump=bloom
    """.stripMargin,
    """
      |-Xmx2G
      |-Dhoodlump=bloom
    """.stripMargin,
  )

}
