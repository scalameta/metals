package scala.meta.internal.jpc

class DuplicatePatchModuleException(val cause: Throwable)
    extends RuntimeException(
      "--patch-module specified more than once with the same stateful javac compiler instance. The fix for this issue is to try again with a new JavaPruneCompiler instance."
    ) {
  override def fillInStackTrace(): Throwable = this
}
