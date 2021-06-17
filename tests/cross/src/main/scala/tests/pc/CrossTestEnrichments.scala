package tests.pc

object CrossTestEnrichments {
  implicit class XtensionStringCross(s: String) {
    def triplequoted: String = s.replace("'''", "\"\"\"")

    def removeRanges: String =
      s.replace("<<", "")
        .replace(">>", "")
        .replaceAll("/\\*.+\\*/", "")

    def removePos: String = s.replace("@@", "")
  }
}
