package tests

import scala.meta.internal.metals.{BuildInfo => V}

class OutlineLspSuite extends BaseNonCompilingLspSuite("outline") {
  override val scalaVersion: String = V.scala213
  override val saveAfterChanges: Boolean = false
  override val scala3Diagnostics = false
}
