class A >>region>>{
  val field = >>region>>{

    ???
  }<<region<<

  def method(a:Int) = >>region>>{

  }<<region<<

  def longSingleLineBlock = >>region>>{ ???; ???; ???; }<<region<<

  def mappedSequence = Seq().map >>region>>{
    x => x.toString()
  }<<region<<

  def collapsedBlock = {}

  class CollapsedType {}
}<<region<<
