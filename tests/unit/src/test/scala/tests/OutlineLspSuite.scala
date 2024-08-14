package tests

class OutlineLspSuite extends BaseNonCompilingLspSuite("outline") {
  override val scalaVersionConfig: String = ""
  override val saveAfterChanges: Boolean = false
  override val scala3Diagnostics = false
}
