package scala.meta.internal.metals.testResults

sealed trait TestCaseStatus {
  def value: String
}

object TestCaseStatus {

  case object Passed extends TestCaseStatus {
    override val value: String = "passed"
  }
  case object Failed extends TestCaseStatus {
    override val value: String = "failed"
  }
  case object Skipped extends TestCaseStatus {
    override val value: String = "skipped"
  }

  def fromString(value: String): Option[TestCaseStatus] =
    value match {
      case Passed.value => Some(Passed)
      case Failed.value => Some(Failed)
      case Skipped.value => Some(Skipped)
      case _ => None
    }
}
