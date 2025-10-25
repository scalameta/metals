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
    """|longNameYouWillNotRemember: Long
       |long2Long(x: Long): lang.Long
       |longArrayOps(xs: Array[Long]): ArrayOps[Long]
       |longWrapper(x: Long): RichLong
       |Long scala
       |Long2long(x: lang.Long): Long
       |wrapLongArray(xs: Array[Long]): ArraySeq.ofLong
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|longNameYouWillNotRemember: Long
           |long2Long(x: Long): lang.Long
           |longArrayOps(xs: Array[Long]): ArrayOps[Long]
           |longWrapper(x: Long): RichLong
           |Long scala
           |Long2long(x: lang.Long): Long
           |wrapLongArray(xs: Array[Long]): WrappedArray[Long]
           |readLong(): Long
           |""".stripMargin,
      "2.12" ->
        """longNameYouWillNotRemember: Long
          |long2Long(x: Long): lang.Long
          |longArrayOps(xs: Array[Long]): ArrayOps.ofLong
          |longWrapper(x: Long): RichLong
          |Long scala
          |Long2long(x: lang.Long): Long
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
    "lower case query should still only match for segments, so 'ill' should only bring up the long name starting with 'ill'",
    """
      |object A {
      | def test(longNameYouWillNotRemember: Long): Unit = {
      |   val foo = ill@@
      | }
      |}""".stripMargin,
    """|IllegalAccessError java.lang
       |IllegalAccessException java.lang
       |IllegalArgumentException java.lang
       |IllegalCallerException java.lang
       |IllegalMonitorStateException java.lang
       |IllegalStateException java.lang
       |IllegalThreadStateException java.lang
       |""".stripMargin
  )

  check(
    "case-backticked",
    """package kase
      |class SomeModel {
      |  def `Sentence Case Metric` = ...
      |  def `Sentence Case Metric (12m Average)` = `Sentence Case Metric`.rollingAvg(12.months)
      |  def `Sentence Case Metric (6m Average)` = `Sentence Case Metric`.rollingAvg(6.months)
      |  def `Aggregated Sentence Case Metric` = sente@@
      |}""".stripMargin,
    """|`Sentence Case Metric`: Null
       |`Sentence Case Metric (6m Average)`: Any
       |""".stripMargin
  )

  check(
    "case-backticked2",
    """package kase
      |class SomeModel {
      |  def `Sentence Case Metric` = ...
      |  def `Sentence Case Metric (12m Average)` = `Sentence Case Metric`.rollingAvg(12.months)
      |  def `Sentence Case Metric (6m Average)` = `Sentence Case Metric`.rollingAvg(6.months)
      |  def `Aggregated Sentence Case Metric` = SenteCa@@
      |}""".stripMargin,
    """|`Aggregated Sentence Case Metric`: Any
       |`Sentence Case Metric`: Null
       |`Sentence Case Metric (6m Average)`: Any
       |""".stripMargin
  )

  /* This doesn't match because we expect the substring to be continuous, otherwise we might match too much results.
   * We can match non continuous substring only if they start with Capital letter.
   */
  check(
    "case-backticked3",
    """package kase
      |class SomeModel {
      |  def `Sentence Case Metric` = ...
      |  def `Sentence Case Metric (12m Average)` = `Sentence Case Metric`.rollingAvg(12.months)
      |  def `Sentence Case Metric (6m Average)` = `Sentence Case Metric`.rollingAvg(6.months)
      |  def `Aggregated Sentence Case Metric` = sente12@@
      |}""".stripMargin,
    """|""".stripMargin
  )

}
