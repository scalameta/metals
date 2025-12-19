package tests

import scala.meta.internal.metals.Configs.DefinitionProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

import org.eclipse.lsp4j.Location

class ProtobufDefinitionLspSuite
    extends BaseLspSuite("protobuf-definition")
    with BaseSourcePathSuite {
  override def initializeGitRepo: Boolean = true
  override def userConfig: UserConfiguration = super.userConfig.copy(
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    definitionProviders = DefinitionProviderConfig(List("protobuf")),
  )

  test("protobuf-definition") {
    cleanWorkspace()
    val main = "a/src/main/scala/a/Main.scala"
    def isProtobufLocation(loc: Location): Boolean =
      loc.getUri().endsWith(".proto")
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": { }
            |}
            |/a/src/main/protobuf/example.proto
            |syntax = "proto3";
            |package a;
            |message Example {
            |  enum ExampleType {
            |    EXAMPLE_TYPE_UNSPECIFIED = 0;
            |    EXAMPLE_TYPE_1 = 1;
            |    EXAMPLE_TYPE_2 = 2;
            |  }
            |  string name = 1;
            |  repeated string tags = 2;
            |  ExampleType example_type = 3;
            |}
            |message GetExampleRequest {
            |  Example example = 1;
            |}
            |service ExampleService {
            |  rpc GetExample(GetExampleRequest) returns (Example);
            |}
            |/a/src/main/scala/a/Example.scala
            | // Protofile syntax: PROTO2
            | // testing-only-source: a/src/main/protobuf/example.proto
            |package a
            |case class Example(
            |  name: String = "",
            |  tags: List[String] = Nil,
            |  exampleType: ExampleType = ExampleType.EXAMPLE_TYPE_UNSPECIFIED,
            |) {
            |  def withName(name: String): Example = ???
            |  def withTags(tags: List[String]): Example = ???
            |  def withExampleType(exampleType: ExampleType): Example = ???
            |}
            |object Example {
            |  def withName(name: String): Example = ???
            |  def withTags(tags: List[String]): Example = ???
            |  def withExampleType(exampleType: ExampleType): Example = ???
            |}
            |case class ExampleType(value: Int)
            |object ExampleType {
            |  val EXAMPLE_TYPE_UNSPECIFIED = ExampleType(0)
            |  val EXAMPLE_TYPE_1 = ExampleType(1)
            |  val EXAMPLE_TYPE_2 = ExampleType(2)
            |}
            |case class GetExampleRequest(
            |  example: Example,
            |)
            |trait ExampleService {
            |  def getExample(request: GetExampleRequest): Example
            |}
            |/$main
            |package a
            |object Main {
            |  def example = Example()
            |    .withName("name")
            |    .withTags(List("tag1", "tag2"))
            |    .withExampleType(ExampleType.EXAMPLE_TYPE_1)
            |  def name = example.name
            |  def handle(service: ExampleService): Unit = {
            |    service.getExample(GetExampleRequest())
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(main)
      _ <- server.assertDefinition(
        main,
        "Examp@@le()",
        """|a/src/main/protobuf/example.proto:3:9: definition
           |message Example {
           |        ^^^^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
      _ <- server.assertDefinition(
        main,
        "withNam@@e(",
        """|a/src/main/protobuf/example.proto:9:10: definition
           |  string name = 1;
           |         ^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
      _ <- server.assertDefinition(
        main,
        "example.na@@me",
        """|a/src/main/protobuf/example.proto:9:10: definition
           |  string name = 1;
           |         ^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
    } yield ()
  }
}
