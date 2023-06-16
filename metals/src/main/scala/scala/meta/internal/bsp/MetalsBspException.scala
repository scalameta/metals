package scala.meta.internal.metals

final class MetalsBspException(val tryingToGet: String, cause: Throwable)
    extends Exception(
      s"BSP connection failed in the attempt to get: $tryingToGet",
      cause,
    )
