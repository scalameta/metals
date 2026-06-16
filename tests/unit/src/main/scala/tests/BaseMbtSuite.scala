package tests

trait BaseMbtSuite {
  protected def escapeMbtFile(mbtFile: String): String = {

    mbtFile
      .replaceAll(
        """"(jar|sources)":\s*"[^"]+"""",
        """"$1": "<$1-path>"""",
      )
      .replaceAll(
        """"(classDirectories|testClassDirectories)":\s*\[\s*"[^"]+"\s*\]""",
        """"$1": ["<$1-path>"]""",
      )
      .replaceAll(
        """"(javaHome)":\s*"[^"]+"""",
        """"$1": "<$1-path>"""",
      )
  }
}
