package tests

trait BaseMbtSuite {
  protected def escapeMbtFile(mbtFile: String): String = {

    mbtFile.replaceAll(
      """"(jar|sources)":\s*"[^"]+"""",
      """"$1": "<$1-path>"""",
    )
  }
}
