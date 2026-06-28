package tests

import scala.meta.internal.metals.debug.server.DebugeeProject

final class DebugeeProjectSuite extends BaseSuite {
  test("user-jvm-options-take-precedence-over-build-server") {
    val project = DebugeeProject(
      scalaVersion = None,
      name = "example",
      modules = Nil,
      libraries = Nil,
      unmanagedEntries = Nil,
      runClassPath = Nil,
      environmentVariables = Map.empty,
      jvmOptions = List("-Duser.dir=/build/server", "-Xss4M"),
    )

    // build server options come first, user options last, so that the user's
    // options win the JVM's last-occurrence-wins resolution (e.g. -Duser.dir)
    assertEquals(
      project.jvmOptionsWith(List("-Duser.dir=/user/choice", "-Xmx2G")),
      List(
        "-Duser.dir=/build/server",
        "-Xss4M",
        "-Duser.dir=/user/choice",
        "-Xmx2G",
      ),
    )
  }

  test("deduplicates-build-server-options-threaded-through-user-list") {
    // The MCP test runner passes the BSP jvmOptions through both
    // `project.jvmOptions` and the `ScalaTestSuites` (user) list, so the same
    // build server option arrives twice. It must be deduplicated, and a
    // differing user/workspace value must still win the last-occurrence race.
    val project = DebugeeProject(
      scalaVersion = None,
      name = "example",
      modules = Nil,
      libraries = Nil,
      unmanagedEntries = Nil,
      runClassPath = Nil,
      environmentVariables = Map.empty,
      jvmOptions = List("-Duser.dir=/build/server"),
    )

    assertEquals(
      project.jvmOptionsWith(
        List("-Duser.dir=/workspace", "-Duser.dir=/build/server")
      ),
      List("-Duser.dir=/build/server", "-Duser.dir=/workspace"),
    )
  }
}
