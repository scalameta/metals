package scala.meta.internal.metals

class BuildServerInitializeException(e: Throwable)
    extends Exception("Failed to connect to build server", e)
