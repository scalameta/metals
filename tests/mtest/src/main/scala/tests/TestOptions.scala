package tests

case class TestOptions(name: String, tags: List[Tag]) {
  def expectedToFail: TestOptions = tag(Tag.ExpectFailure)
  def tag(t: Tag): TestOptions = copy(tags = t :: tags)
}

trait TestOptionsConversions {
  implicit def testOptionsFromString(name: String): TestOptions =
    TestOptions(name, Nil)
}
