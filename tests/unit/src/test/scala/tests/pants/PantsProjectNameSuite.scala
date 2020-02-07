package tests.pantsbuild

import tests.BaseSuite
import scala.meta.internal.pantsbuild.PantsConfiguration

class PantsProjectNameSuite extends BaseSuite {
  def checkTargetsName(
      name: String,
      targets: List[String],
      expected: String
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val obtained = PantsConfiguration.outputFilename(targets)
      assertNoDiff(obtained, expected)
    }
  }

  checkTargetsName(
    "basic",
    List("stats::"),
    "stats"
  )

  checkTargetsName(
    "two",
    List("stats::", "cache::"),
    "stats__cache"
  )

  checkTargetsName(
    "two-nested",
    List("stats/server::", "cache/server::"),
    "stats.server__cache.server"
  )

  checkTargetsName(
    "long",
    List(
      "stats/inner/inner/inner/foobar/loooooooooooooooooooooooooong/naaaaaaaaaaaaaaaaaaaammmmmmmmmmmmmmeeeeeeeeeeee/server::"
    ),
    "stats.inner.inner.inner.foobar-eeeeeeee.server-50B8C988D9AE"
  )

  checkTargetsName(
    "long-two",
    List(
      "stats/inner/inner/inner/foobar/loooooooooooooooooooooooooong/naaaaaaaaaaaaaaaaaaaammmmmmmmmmmmmmeeeeeeeeeeee/server::",
      "cache/inner/inner/inner/foobar/loooooooooooooooooooooooooong/naaaaaaaaaaaaaaaaaaaammmmmmmmmmmmmmeeeeeeeeeeee/server::"
    ),
    "stats.inner.inner.inner.foobar-eeeeeeee.server-C84EE1FE8218"
  )
}
