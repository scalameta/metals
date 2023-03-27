package scala.meta.internal.pc

import scala.meta.pc.Node

case class TokenNode(
    start: Int,
    end: Int,
    tokenType: Int,
    tokenModifier: Int
) extends Node
