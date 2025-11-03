package tests.mbt

import scala.meta.internal.metals.mbt.OID

class OIDSuite extends munit.FunSuite {
  test("fromText") {
    // Taken from bin/bloop.json in the Metals repo.
    val text = """{
  "javaOptions": ["-Xmx1G", "-Xss16m"]
}
"""
    val oid = OID.fromText(text)
    assertEquals(oid, "1f30594bc0798678457920a78b1ef25f387d05ba")
  }
}
