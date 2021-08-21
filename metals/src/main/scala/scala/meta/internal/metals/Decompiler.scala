package scala.meta.internal.metals

import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}
import java.{util => ju}

class Decompiler(
    compilers: Compilers
) {

  private def parseJsonParams(
      commandParams: l.ExecuteCommandParams
  ): Option[String] = for {
    args <- Option(commandParams.getArguments)
    arg <- args.asScala.headOption.collect {
      case js: JsonPrimitive if js.isString =>
        js
    }
  } yield arg.getAsString()

  def decompile(
      params: l.ExecuteCommandParams
  )(implicit
      ec: ExecutionContext
  ): Future[ju.Optional[l.ExecuteCommandParams]] = {
    val command = parseJsonParams(params) match {
      case Some(path) =>
        val uri = Paths.get(path).toUri
        compilers.decompile(uri)
      case None => Future(ju.Optional.empty[l.ExecuteCommandParams]())
    }
    command
  }
}
