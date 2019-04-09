class A >>region>>{
  val field = >>region>>{




    ???
  }<<region<<

  def method(a:Int) = >>region>>{





  }<<region<<

  def longSingleLineBlock = { ???; ???; ???; }

  def chain = Seq(1).map>>region>>{
    x =>
    >>region>>x + 1
     + 1
     + 1
     + 1
     + 1
     + 1
     + 1<<region<<
  }<<region<<.map>>region>>{
    _ + 1
    + 1
    + 1
    + 1
    + 1
    + 1
  }<<region<<

  def mappedSequence = Seq().map >>region>>{
    x => x.toString()




  }<<region<<

  def collapsedBlock = {}

  class CollapsedType {}
}<<region<<
