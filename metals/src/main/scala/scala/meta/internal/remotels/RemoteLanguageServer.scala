package scala.meta.internal.remotels

import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionResult
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ReferencesResult
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MD5
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}

class RemoteLanguageServer(
    workspace: () => AbsolutePath,
    userConfig: () => UserConfiguration,
    serverConfig: MetalsServerConfig,
    buffers: Buffers,
    buildTargets: BuildTargets
)(implicit ec: ExecutionContext) {
  val timeout: Duration = Duration(serverConfig.remoteTimeout)
  def isEnabledForPath(path: AbsolutePath): Boolean =
    userConfig().remoteLanguageServer.isDefined &&
      buildTargets.inverseSources(path).isEmpty

  def referencesBlocking(
      params: l.ReferenceParams
  ): Option[ReferencesResult] = {
    // NOTE(olafur): we provide this blocking implementation because it requires
    // a significant refactoring to make the reference provider and its
    // dependencies (including rename provider) asynchronous. The remote
    // language server returns `Future.successful(None)` when it's disabled so
    // this isn't even blocking for normal usage of Metals.
    Await.result(references(params), timeout)
  }
  def references(
      params: l.ReferenceParams
  ): Future[Option[ReferencesResult]] =
    blockingRequest { url =>
      for {
        locations <- postLocationRequest(
          url,
          params.toJsonObject,
          "textDocument/references"
        )
      } yield ReferencesResult(Symbols.None, locations.asScala)
    }

  def definition(
      params: l.TextDocumentPositionParams
  ): Future[Option[DefinitionResult]] =
    blockingRequest { url =>
      for {
        locations <- postLocationRequest(
          url,
          params.toJsonObject,
          "textDocument/definition"
        )
      } yield DefinitionResult(locations, Symbols.None, None, None)
    }

  private def blockingRequest[T](fn: String => Option[T]): Future[Option[T]] = {
    userConfig().remoteLanguageServer match {
      case Some(url) => Future(concurrent.blocking(fn(url)))
      case None => Future.successful(None)
    }
  }

  private def postLocationRequest(
      url: String,
      params: JsonObject,
      method: String
  ): Option[ju.List[Location]] = {
    val maybeResponse = Try(
      requests.post(
        url,
        data = asRemoteParameters(params, method).toString(),
        headers = List("Content-Type" -> "application/json")
      )
    )
    maybeResponse.toEither.left.foreach { error =>
      scribe.error(s"remote: request failed '${error.getMessage()}'")
    }
    for {
      response <- maybeResponse.toOption
      if response.statusCode == 200
      result <- response.text().parseJson.as[RemoteLocationResult].toOption
      locations <- Option(result.result)
    } yield {
      locations.forEach { location =>
        val relativeUri = location.getUri().stripPrefix("source://")
        val absoluteUri = workspace().resolve(relativeUri).toURI.toString
        location.setUri(absoluteUri)
      }
      locations
    }
  }

  /**
   * Creates a JSON request according to https://scalameta.org/metals/docs/contributors/remote-language-server.html */
  private def asRemoteParameters(
      params: JsonObject,
      method: String
  ): JsonObject = {
    val textDocument = params.get("textDocument").getAsJsonObject()
    val absolutePath = textDocument.get("uri").getAsString.toAbsolutePath
    val relativeUri =
      absolutePath.toRelative(workspace()).toURI(isDirectory = false)
    val md5 = MD5.compute(buffers.get(absolutePath).getOrElse(""))

    textDocument.add("uri", new JsonPrimitive(s"source://$relativeUri"))
    textDocument.add("md5", new JsonPrimitive(md5))

    val result = new JsonObject()
    result.add("method", new JsonPrimitive(method))
    result.add("params", params)
    result.add("id", new JsonPrimitive(10))
    result
  }

}
