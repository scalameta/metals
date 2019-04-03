package tests

import tests.MutableText.Insert

object MutableTextSuite extends BaseSuite {
  test("multiple-inserts") {
    val text = MutableText("aaa")
    text.updateWith(Seq(Insert(0, 1, "b"), Insert(0, 2, "b")))

    assertEquals(text.toString, "ababa")
  }
}
