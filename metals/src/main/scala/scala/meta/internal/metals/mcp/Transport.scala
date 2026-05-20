package scala.meta.internal.metals.mcp

sealed trait Transport

object Transport {
  case object Http extends Transport
  case object Stdio extends Transport

  def fromString(s: String): Option[Transport] =
    s.toLowerCase match {
      case "http" => Some(Http)
      case "stdio" => Some(Stdio)
      case _ => None
    }
}
