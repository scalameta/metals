package scala.meta.internal.metals.buildserver

class BuildServerInitializeException(e: Throwable)
    extends Exception("Failed to connect to build server", e)
