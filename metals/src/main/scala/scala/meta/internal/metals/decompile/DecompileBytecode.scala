package scala.meta.internal.metals.decompile

import scala.concurrent.Future

import scala.meta.io.AbsolutePath

trait DecompileBytecode {
  def decompilePath(
      path: AbsolutePath,
      extraClassPath: List[AbsolutePath],
  ): Future[Either[String, String]]
  def decompile(
      className: String,
      extraClassPath: List[AbsolutePath],
  ): Future[Either[String, String]]
}

object DecompileBytecode {
  val cfr: DecompileBytecode = new CfrDecompiler()
}
