package tests

import scala.meta.dialects
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol

class JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    JdkSources().right.get
  }

  test("index-src.zip") {
    val jdk = JdkSources().right.get
    val symbolIndex = OnDemandSymbolIndex.empty()

    symbolIndex.addSourceJar(jdk, dialects.Scala213)

    val pathsDef = symbolIndex.definition(Symbol("java/nio/file/Paths#"))
    assert(pathsDef.isDefined, "Cannot find java/nio/file/Paths#")

    val swingBoxDef =
      symbolIndex.definition(Symbol("javax/swing/Box."))
    assert(swingBoxDef.isDefined, "Cannot find javax/swing/Box.")

  }
}
