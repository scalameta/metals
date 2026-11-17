package tests.debug

import java.util.jar.JarFile

import scala.meta.internal.metals.debug.JUnitPlatformTestMain
import scala.meta.internal.metals.debug.TestFrameworkUtils

import bloop.config.Config
import tests.BaseSuite

class JupiterFrameworkSuite extends BaseSuite {
  test("runner-classpath-is-a-standalone-jar") {
    val archive = new JarFile(JUnitPlatformTestMain.classpathEntry().toFile)
    try {
      assert(
        archive.getEntry(
          "scala/meta/internal/metals/debug/JUnitPlatformTestMain.class"
        ) != null
      )
    } finally archive.close()
  }

  test("bsp-jupiter-framework-keeps-its-own-runner") {
    assertEquals(
      TestFrameworkUtils.from(Some("Jupiter")),
      TestFrameworkUtils.JupiterTestFramework,
    )
    assertEquals(
      TestFrameworkUtils.from(Some("JUnit")),
      Config.TestFramework.JUnit,
    )
    assert(
      TestFrameworkUtils.canResolveTests(
        TestFrameworkUtils.JupiterTestFramework
      )
    )
  }
}
