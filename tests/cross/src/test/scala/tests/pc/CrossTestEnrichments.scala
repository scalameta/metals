package tests.pc

object CrossTestEnrichments {
  implicit class XtensionStringCross(s: String) {
    def triplequoted: String = s.replaceAllLiterally("'''", "\"\"\"")
  }
}
