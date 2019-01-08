package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Trees

object TestingTrees {
  def apply(buffers: Buffers = Buffers()): Trees =
    new Trees(buffers, TestingDiagnostics(buffers = buffers))
}
