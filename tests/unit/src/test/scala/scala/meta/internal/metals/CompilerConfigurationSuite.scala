package scala.meta.internal.metals

import tests.BaseSuite

class CompilerConfigurationSuite extends BaseSuite {

  test("java-pc-options") {
    val options = List(
      "--release", "21", "-Xlint", "-Xlint:deprecation,unchecked", "-Xlintfile",
      "-Werror",
    )

    assertEquals(
      CompilerConfiguration.javaPcOptions(options, includeAll = false),
      List("-Xlint", "-Xlint:deprecation,unchecked"),
    )
    assertEquals(
      CompilerConfiguration.javaPcOptions(options, includeAll = true),
      options,
    )
  }
}
