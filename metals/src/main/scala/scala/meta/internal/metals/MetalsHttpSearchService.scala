package scala.meta.internal.metals

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import scala.meta.internal.query.ClassInspectResult
import scala.meta.internal.query.MethodInspectResult
import scala.meta.internal.query.QueryEngine
import scala.meta.internal.query.SymbolInspectResult
import scala.meta.internal.query.SymbolType
import scala.meta.internal.query.TemplateInspectResult
import scala.meta.internal.query.TermParamList
import scala.meta.internal.query.TypedParamList

import com.github.plokhotnyuk.jsoniter_scala.core._
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.jsoniter._
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter

case class SearchMatch(
    name: String,
    path: String,
    `type`: String,
)

case class GlobSearchResponse(matches: List[SearchMatch])

case class InspectResponse(
    name: String,
    path: String,
    results: List[InspectResult],
    error: Option[String] = None,
)

case class InspectResult(
    `type`: String,
    path: String,
    visibility: Option[String],
    returnType: Option[String],
    constructors: Option[List[InspectResult]] = None,
    membersFull: Option[List[InspectResult]] = None,
    members: Option[List[String]] = None,
    params: Option[String] = None,
)

case class DocParameter(name: String, description: String)

case class Documentation(
    description: String,
    parameters: List[DocParameter],
    returnValue: Option[String],
    examples: List[String],
)

case class DocsResponse(
    `type`: Option[String],
    name: String,
    path: String,
    documentation: Option[Documentation],
    error: Option[String] = None,
)

case class ErrorResponse(error: ErrorDetails)
case class ErrorDetails(code: String, message: String)

object Codecs {
  import com.github.plokhotnyuk.jsoniter_scala.macros._

  implicit val globSearchResponseCodec: JsonValueCodec[GlobSearchResponse] =
    JsonCodecMaker.make
  implicit val inspectResponseCodec: JsonValueCodec[InspectResponse] =
    JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true))
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
      .in(query[Boolean]("inspectMembers"))
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
            val matches = searchResults.map { res =>
              SearchMatch(res.name, res.path, res.symbolType.name)
            }
            GlobSearchResponse(matches.toList)
          }
        }
      },
      inspectEndpoint.serverLogicSuccess[Future] {
        case (fqcn, inspectMembers) =>
          queryEngine.inspect(fqcn).map {
            case all @ (head :: _) =>
              // Transform the inspect result to InspectResponse
              // This would depend on the specific type of result
              def toRes(info: SymbolInspectResult): InspectResult =
                InspectResult(
                  `type` = info.symbolType.name,
                  path = info.path,
                  visibility = info match {
                    case m: MethodInspectResult => Some(m.visibility)
                    case _ => None
                  },
                  returnType = info match {
                    case m: MethodInspectResult => Some(m.returnType)
                    case _ => None
                  },
                  constructors = info match {
                    case c: ClassInspectResult =>
                      Some(c.constructors.map(toRes))
                    case _ => None
                  },
                  membersFull =
                    if (inspectMembers)
                      info match {
                        case t: TemplateInspectResult =>
                          Some(t.members.map(toRes))
                        case _ => None
                      }
                    else None,
                  members =
                    if (inspectMembers) None
                    else
                      info match {
                        case t: TemplateInspectResult =>
                          Some(t.members.map(_.name))
                        case _ => None
                      },
                  params = info match {
                    case m: MethodInspectResult =>
                      Some(m.parameters.map {
                        case TermParamList(params, prefix) =>
                          val prefixStr = if (prefix == "") "" else s"$prefix "
                          params
                            .map(_.toString)
                            .mkString(s"($prefixStr", ", ", ")")
                        case TypedParamList(params) if params.nonEmpty =>
                          params.map(_.toString).mkString("[", ", ", "]")
                        case _ => ""
                      }.mkString)
                    case _ => None
                  },
                )
              InspectResponse(
                name = head.name,
                path = head.path,
                results = all.map { res => toRes(res) },
              )
            case Nil =>
              InspectResponse(
                name = fqcn.split('.').last,
                path = fqcn,
                results = Nil,
                error = Some("Symbol not found"),
              )
          }
      },
      docsEndpoint.serverLogicSuccess[Future] { fqcn =>
        queryEngine.getDocumentation(fqcn).map {
          case Some(result) =>
            DocsResponse(
              `type` = Some(result.symbolType.name),
              name = result.name,
              path = result.path,
              documentation = result.documentation.map { docs =>
                Documentation(
                  description = docs.description,
                  parameters = docs.params.map { case (name, desc) =>
                    DocParameter(name, desc)
                  },
                  returnValue = docs.returnValue,
                  examples = docs.examples,
                )
              },
            )
          case None =>
            DocsResponse(
              `type` = None,
              name = fqcn.split('.').last,
              path = fqcn,
              documentation = None,
              error = Some("Symbol not found"),
            )
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
