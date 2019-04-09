class A {
  val field = {




    ???
  }

  def method(a:Int) = {





  }

  def longSingleLineBlock = { ???; ???; ???; }

  def chain = Seq(1).map{
    x =>
    x + 1
     + 1
     + 1
     + 1
     + 1
     + 1
     + 1
  }.map{
    _ + 1
    + 1
    + 1
    + 1
    + 1
    + 1
  }

  def mappedSequence = Seq().map {
    x => x.toString()




  }

  def collapsedBlock = {}

  class CollapsedType {}
}
