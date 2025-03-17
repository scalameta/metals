package scala.meta.internal.metals

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import scala.meta.internal.query.ClassOrObjectSearchResult
import scala.meta.internal.query.PackageSearchResult
import scala.meta.internal.query.QueryEngine
import scala.meta.internal.query.SymbolType
import scala.meta.internal.query.WorkspaceSymbolSearchResult

import com.github.plokhotnyuk.jsoniter_scala.core._
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.jsoniter._
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter

sealed trait SearchMatch {
  def name: String
  def path: String
  def `type`: String
}

case class PackageMatch(name: String, path: String) extends SearchMatch {
  val `type`: String = "package"
}

case class ClassMatch(name: String, path: String) extends SearchMatch {
  val `type`: String = "class"
}

case class ObjectMatch(name: String, path: String) extends SearchMatch {
  val `type`: String = "object"
}

case class FunctionMatch(name: String, path: String) extends SearchMatch {
  val `type`: String = "function"
}

case class GlobSearchResponse(matches: List[SearchMatch])

case class Method(
    name: String,
    visibility: String,
    parameters: List[String],
    returnType: String,
)

case class InspectResponse(
    `type`: String,
    name: String,
    path: String,
    constructors: Option[List[String]] = None,
    methods: Option[List[Method]] = None,
)

case class DocParameter(name: String, description: String)

case class Documentation(
    description: String,
    parameters: List[DocParameter],
    returnValue: String,
    examples: List[String],
)

case class DocsResponse(
    `type`: String,
    name: String,
    path: String,
    documentation: Documentation,
)

case class ErrorResponse(error: ErrorDetails)
case class ErrorDetails(code: String, message: String)

object Codecs {
  import com.github.plokhotnyuk.jsoniter_scala.macros._

  implicit val globSearchResponseCodec: JsonValueCodec[GlobSearchResponse] =
    JsonCodecMaker.make
  implicit val inspectResponseCodec: JsonValueCodec[InspectResponse] =
    JsonCodecMaker.make
  implicit val docsResponseCodec: JsonValueCodec[DocsResponse] =
    JsonCodecMaker.make
  implicit val errorResponseCodec: JsonValueCodec[ErrorResponse] =
    JsonCodecMaker.make
}

trait MetalsHttpSearchService extends Cancelable {
  def cancel(): Unit
}

object MetalsHttpSearchService {
  import Codecs._
  import Codec._

  implicit val symbolTypeCodec: PlainCodec[SymbolType] =
    Codec.string
      .mapEither(str =>
        SymbolType.values
          .find(_.name == str)
          .map(Right(_))
          .getOrElse(Left(s"Invalid symbol type: $str"))
      )(_.name)

  private val baseEndpoint = endpoint
    .errorOut(
      oneOf[ErrorResponse](
        oneOfVariant(
          statusCode(StatusCode.BadRequest).and(jsonBody[ErrorResponse])
        ),
        oneOfVariant(
          statusCode(StatusCode.NotFound).and(jsonBody[ErrorResponse])
        ),
        oneOfVariant(
          statusCode(StatusCode.InternalServerError).and(
            jsonBody[ErrorResponse]
          )
        ),
      )
    )

  def apply(
      queryEngine: QueryEngine,
      host: String,
      port: Int,
  ): MetalsHttpSearchService = {

    val globSearchEndpoint = baseEndpoint.get
      .in("api" / "glob-search")
      .in(query[String]("query"))
      .in(query[List[SymbolType]]("symbolType").map(_.toSet)(_.toList))
      .out(jsonBody[GlobSearchResponse])

    val inspectEndpoint = baseEndpoint.get
      .in("api" / "inspect")
      .in(query[String]("fqcn"))
      .out(jsonBody[InspectResponse])

    val docsEndpoint = baseEndpoint.get
      .in("api" / "docs")
      .in(query[String]("fqcn"))
      .out(jsonBody[DocsResponse])

    val endpoints = List(
      globSearchEndpoint.serverLogicSuccess[Future] {
        case (query, filterSymbolTypes) => {
          Future {
            val searchResults = queryEngine.globSearch(query, filterSymbolTypes)

            val matches = searchResults.map {
              case PackageSearchResult(name, path) =>
                PackageMatch(name, path)
              case ClassOrObjectSearchResult(name, path, symbolType) =>
                symbolType match {
                  case SymbolType.Class => ClassMatch(name, path)
                  case SymbolType.Object => ObjectMatch(name, path)
                  case _ => ClassMatch(name, path) // Default fallback
                }
              case WorkspaceSymbolSearchResult(name, path, symbolType, _) =>
                symbolType match {
                  case SymbolType.Function => FunctionMatch(name, path)
                  case SymbolType.Method =>
                    FunctionMatch(
                      name,
                      path,
                    ) // Treating methods as functions for UI
                  case SymbolType.Class => ClassMatch(name, path)
                  case SymbolType.Object => ObjectMatch(name, path)
                  case SymbolType.Package => PackageMatch(name, path)
                  case _ => FunctionMatch(name, path) // Default fallback
                }
            }

            GlobSearchResponse(matches.toList)
          }
        }
      },
      inspectEndpoint.serverLogicSuccess[Future] { fqcn =>
        Future {
          queryEngine.inspect(fqcn) match {
            case Some(result) =>
              // Transform the inspect result to InspectResponse
              // This would depend on the specific type of result
              InspectResponse(
                `type` = result.symbolType.name,
                name = result.name,
                path = result.path,
                // Additional fields would be populated based on result type
                constructors = None,
                methods = None,
              )
            case None =>
              throw new Exception(s"Symbol not found: $fqcn")
          }
        }
      },
      docsEndpoint.serverLogicSuccess[Future] { fqcn =>
        Future {
          queryEngine.getDocumentation(fqcn) match {
            case Some(docs) =>
              DocsResponse(
                `type` =
                  "unknown", // Would be determined from the actual symbol
                name = fqcn.substring(fqcn.lastIndexOf('.') + 1),
                path = fqcn,
                documentation = Documentation(
                  description = docs.description,
                  parameters = docs.parameters.map { case (name, desc) =>
                    DocParameter(name, desc)
                  },
                  returnValue = docs.returnValue,
                  examples = docs.examples,
                ),
              )
            case None =>
              throw new Exception(s"Documentation not found for: $fqcn")
          }
        }
      },
    )

    val pekkoRoute = PekkoHttpServerInterpreter().toRoute(endpoints)

    import org.apache.pekko.actor.ActorSystem
    import org.apache.pekko.http.scaladsl.Http
    import scala.concurrent.Await
    import scala.concurrent.duration._

    implicit val system = ActorSystem("metals-http-search-system")
    implicit val executionContext = system.dispatcher

    val binding = Http().newServerAt(host, port).bind(pekkoRoute)
    scribe.info(s"HTTP Search server started at http://$host:$port")

    new MetalsHttpSearchService {
      override def cancel(): Unit = {
        scribe.info("Stopping HTTP Search server")
        try {
          Await.result(binding.flatMap(_.terminate(10.seconds)), 15.seconds)
          Await.result(system.terminate(), 5.seconds)
        } catch {
          case e: Exception =>
            scribe.error("Error stopping HTTP Search server", e)
        }
      }
    }
  }
}
