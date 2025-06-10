package tests.pc

import tests.BaseCompletionSuite

class CompletionCaseInsensitiveSuite extends BaseCompletionSuite {

  check(
    "use case insensitive comparison for first character of query",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = nam@@
      | }
      |}""".stripMargin,
    """longNameYouWillNotRemember: Long
      |deprecatedName scala
      |""".stripMargin
  )

  check(
    "use case insensitive comparison for first character of query at the beginning of the symbol",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = lon@@
      | }
      |}""".stripMargin,
    """longNameYouWillNotRemember: Long
      |long2Long(x: Long): lang.Long
      |longArrayOps(xs: Array[Long]): ArrayOps[Long]
      |longWrapper(x: Long): RichLong
      |wrapLongArray(xs: Array[Long]): ArraySeq.ofLong
      |""".stripMargin,
    compat = Map(
      "2.12" ->
        """longNameYouWillNotRemember: Long
          |long2Long(x: Long): lang.Long
          |longArrayOps(xs: Array[Long]): ArrayOps.ofLong
          |longWrapper(x: Long): RichLong
          |wrapLongArray(xs: Array[Long]): WrappedArray[Long]
          |readLong(): Long
          |""".stripMargin
    )
  )

  check(
    "use case insensitive comparison for first character of query, correctly narrow result using later uppercase in query",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = namY@@
      | }
      |}""".stripMargin,
    """longNameYouWillNotRemember: Long
      |""".stripMargin
  )

  check(
    "use case insensitive comparison for first character of query, correctly narrow result using uppercase not directly following matched segment",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = namRem@@
      | }
      |}""".stripMargin,
    """longNameYouWillNotRemember: Long
      |""".stripMargin
  )

  check(
    "lower case query should still only match for segments, so 'ill' should not bring up the long name",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = ill@@
      | }
      |}""".stripMargin,
    ""
  )

}
