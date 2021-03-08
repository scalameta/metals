package scala.meta.internal.metals

case class MetalsBspException(tryingToGet: String, msg: String)
    extends Exception(
      s"BSP connection failed in the attempt to get: $tryingToGet.  $msg"
    )
