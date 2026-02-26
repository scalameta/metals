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
            |  def typ = example.exampleType
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
      // Snake-case field (example_type -> exampleType): accessing via val
      _ <- server.assertDefinition(
        main,
        "example.exampleTy@@pe",
        """|a/src/main/protobuf/example.proto:11:15: definition
           |  ExampleType example_type = 3;
           |              ^^^^^^^^^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
    } yield ()
  }

  // Test for nested enum inside nested message (2 levels of message nesting)
  // Proto: OuterMessage.InnerMessage.SyncState
  // Scala: object OuterMessage { object InnerMessage { sealed trait SyncState } }
  test("nested-enum-definition") {
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
            |/a/src/main/protobuf/messages.proto
            |syntax = "proto3";
            |package a;
            |message OuterMessage {
            |  message InnerMessage {
            |    enum SyncState {
            |      SYNC_STATE_UNSPECIFIED = 0;
            |      SYNC_STATE_ACTIVE = 1;
            |    }
            |    SyncState sync_state = 1;
            |  }
            |  InnerMessage inner = 1;
            |}
            |/a/src/main/scala/a/OuterMessage.scala
            | // Protofile syntax: PROTO3
            | // testing-only-source: a/src/main/protobuf/messages.proto
            |package a
            |case class OuterMessage(
            |  inner: OuterMessage.InnerMessage
            |)
            |object OuterMessage {
            |  case class InnerMessage(
            |    syncState: OuterMessage.InnerMessage.SyncState
            |  )
            |  object InnerMessage {
            |    sealed trait SyncState
            |    object SyncState {
            |      case object SYNC_STATE_UNSPECIFIED extends SyncState
            |      case object SYNC_STATE_ACTIVE extends SyncState
            |    }
            |  }
            |}
            |/$main
            |package a
            |object Main {
            |  val state: OuterMessage.InnerMessage.SyncState =
            |    OuterMessage.InnerMessage.SyncState.SYNC_STATE_ACTIVE
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(main)
      _ <- server.assertDefinition(
        main,
        "Sync@@State =",
        """|a/src/main/protobuf/messages.proto:5:10: definition
           |    enum SyncState {
           |         ^^^^^^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
    } yield ()
  }

  // Test for deeply nested enum (4 levels: ConfigSettings.AutoScheduler.Schedule.DayOfWeek)
  test("deeply-nested-enum-definition") {
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
            |/a/src/main/protobuf/config.proto
            |syntax = "proto3";
            |package a;
            |message ConfigSettings {
            |  message AutoScheduler {
            |    bool enabled = 1;
            |    Schedule schedule = 2;
            |    message Schedule {
            |      TimeSlot time_slot = 1;
            |      message TimeSlot {
            |        DayOfWeek day_of_week = 1;
            |      }
            |      enum DayOfWeek {
            |        DAY_OF_WEEK_UNSPECIFIED = 0;
            |        MONDAY = 1;
            |        TUESDAY = 2;
            |      }
            |    }
            |  }
            |  AutoScheduler auto_scheduler = 1;
            |}
            |/a/src/main/scala/a/ConfigSettings.scala
            | // Protofile syntax: PROTO3
            | // testing-only-source: a/src/main/protobuf/config.proto
            |package a
            |case class ConfigSettings(
            |  autoScheduler: ConfigSettings.AutoScheduler
            |)
            |object ConfigSettings {
            |  case class AutoScheduler(
            |    enabled: Boolean,
            |    schedule: ConfigSettings.AutoScheduler.Schedule
            |  )
            |  object AutoScheduler {
            |    case class Schedule(
            |      timeSlot: ConfigSettings.AutoScheduler.Schedule.TimeSlot
            |    )
            |    object Schedule {
            |      case class TimeSlot(
            |        dayOfWeek: ConfigSettings.AutoScheduler.Schedule.DayOfWeek
            |      )
            |      sealed trait DayOfWeek
            |      object DayOfWeek {
            |        case object DAY_OF_WEEK_UNSPECIFIED extends DayOfWeek
            |        case object MONDAY extends DayOfWeek
            |        case object TUESDAY extends DayOfWeek
            |      }
            |    }
            |  }
            |}
            |/$main
            |package a
            |object Main {
            |  val day: ConfigSettings.AutoScheduler.Schedule.DayOfWeek =
            |    ConfigSettings.AutoScheduler.Schedule.DayOfWeek.MONDAY
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(main)
      _ <- server.assertDefinition(
        main,
        "Day@@OfWeek =",
        """|a/src/main/protobuf/config.proto:12:12: definition
           |      enum DayOfWeek {
           |           ^^^^^^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
    } yield ()
  }

  // Test for different Scala package than proto package (ScalaPB package_name option)
  // Proto package is "example.messages" but Scala package is "com.example.api.messages"
  test("different-scala-package-than-proto") {
    cleanWorkspace()
    val main = "a/src/main/scala/com/example/api/Main.scala"
    def isProtobufLocation(loc: Location): Boolean =
      loc.getUri().endsWith(".proto")
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": { }
            |}
            |/a/src/main/protobuf/messages.proto
            |syntax = "proto3";
            |package example.messages;
            |message DataMessage {
            |  message DataSetting {
            |    enum SyncState {
            |      SYNC_STATE_UNSPECIFIED = 0;
            |      DISABLED = 1;
            |      BOOTSTRAP = 2;
            |      INCREMENTAL_SYNC = 3;
            |    }
            |    SyncState sync_state = 1;
            |  }
            |  repeated DataSetting data_settings = 1;
            |}
            |/a/src/main/scala/com/example/api/DataMessage.scala
            | // Protofile syntax: PROTO3
            | // testing-only-source: a/src/main/protobuf/messages.proto
            |package com.example.api.messages
            |case class DataMessage(
            |  dataSettings: Seq[DataMessage.DataSetting]
            |)
            |object DataMessage {
            |  case class DataSetting(
            |    syncState: DataMessage.DataSetting.SyncState
            |  )
            |  object DataSetting {
            |    sealed trait SyncState
            |    object SyncState {
            |      case object SYNC_STATE_UNSPECIFIED extends SyncState
            |      case object DISABLED extends SyncState
            |      case object BOOTSTRAP extends SyncState
            |      case object INCREMENTAL_SYNC extends SyncState
            |    }
            |  }
            |}
            |/$main
            |package com.example.api
            |import com.example.api.messages.DataMessage
            |object Main {
            |  val state: DataMessage.DataSetting.SyncState =
            |    DataMessage.DataSetting.SyncState.BOOTSTRAP
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(main)
      _ <- server.assertDefinition(
        main,
        "Sync@@State =",
        """|a/src/main/protobuf/messages.proto:5:10: definition
           |    enum SyncState {
           |         ^^^^^^^^^
           |""".stripMargin,
        includeLocation = isProtobufLocation,
      )
    } yield ()
  }
}
