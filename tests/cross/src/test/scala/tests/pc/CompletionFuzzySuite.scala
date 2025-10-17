package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl

import tests.BaseCompletionSuite

class CompletionFuzzySuite extends BaseCompletionSuite {

  check(
    "case",
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

}
