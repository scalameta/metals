package tests.p

/**
 * Tests for Java-to-proto code navigation.
 *
 * These tests verify that Java code importing protobuf-generated classes can
 * navigate to the original proto file definitions.
 */
class ProtoPCJavaSuite extends BaseProtoPCSuite("proto-pc-java") {

  // Test multiple messages from the same proto file in the same package
  test("java-imports-multiple-proto-messages") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/client.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Authentication {
           |  string token = 1;
           |  string user_id = 2;
           |}
           |message Authorization {
           |  string permission = 1;
           |}
           |/a/src/main/java/com/example/Client.java
           |package com.example;
           |import com.example.api.jproto.Authentication;
           |import com.example.api.jproto.Authorization;
           |public class Client {
           |  public void auth(Authentication auth, Authorization authz) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/client.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Client.java")
      _ <- server.didFocus("a/src/main/java/com/example/Client.java")
      // No "package does not exist" diagnostic for proto-generated Java package
      _ = assertNoDiagnostics()
      // Navigate from Authentication import in Java to its definition in client.proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Client.java",
        "import com.example.api.jproto.Authentic@@ation;",
        """|a/src/main/proto/client.proto:5:9: definition
           |message Authentication {
           |        ^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from Authorization import in Java to its definition in client.proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Client.java",
        "import com.example.api.jproto.Authoriz@@ation;",
        """|a/src/main/proto/client.proto:9:9: definition
           |message Authorization {
           |        ^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("java-imports-proto-enum") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/status.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |enum Status {
           |  UNKNOWN = 0;
           |  ACTIVE = 1;
           |  INACTIVE = 2;
           |}
           |/a/src/main/java/com/example/Service.java
           |package com.example;
           |import com.example.api.jproto.Status;
           |public class Service {
           |  public Status getStatus() { return null; }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/status.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Service.java")
      _ <- server.didFocus("a/src/main/java/com/example/Service.java")
      // No "package does not exist" diagnostic for enum import package
      _ = assertNoDiagnostics()
      // Navigate from Status import in Java to enum definition in status.proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Service.java",
        "import com.example.api.jproto.Sta@@tus;",
        """|a/src/main/proto/status.proto:5:6: definition
           |enum Status {
           |     ^^^^^^
           |""".stripMargin,
      )
      // Navigate from return type Status to enum definition in status.proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Service.java",
        "public Sta@@tus getStatus() { return null; }",
        """|a/src/main/proto/status.proto:5:6: definition
           |enum Status {
           |     ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("java-imports-nested-proto-message") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/nested.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Container {
           |  message Inner {
           |    string value = 1;
           |  }
           |  Inner inner = 1;
           |}
           |/a/src/main/java/com/example/Handler.java
           |package com.example;
           |import com.example.api.jproto.Container;
           |public class Handler {
           |  public void handle(Container container) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/nested.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Handler.java")
      _ <- server.didFocus("a/src/main/java/com/example/Handler.java")
      // No diagnostics for import of generated outer message class
      _ = assertNoDiagnostics()
      // Navigate from Container import in Java to message definition in nested.proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Handler.java",
        "import com.example.api.jproto.Contai@@ner;",
        """|a/src/main/proto/nested.proto:5:9: definition
           |message Container {
           |        ^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from method parameter type Container to proto definition
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Handler.java",
        "public void handle(Contai@@ner container) {}",
        """|a/src/main/proto/nested.proto:5:9: definition
           |message Container {
           |        ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("java-package-fallback-to-proto-package") {
    // When java_package is not set, should use proto package
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/simple.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_multiple_files = true;
           |message SimpleMessage {
           |  string name = 1;
           |}
           |/a/src/main/java/com/example/api/Consumer.java
           |package com.example.api;
           |public class Consumer {
           |  public void consume(SimpleMessage msg) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/simple.proto")
      _ <- server.didOpen("a/src/main/java/com/example/api/Consumer.java")
      // Proto package is used as java package when java_package option is absent
      _ <- server.didFocus("a/src/main/java/com/example/api/Consumer.java")
      _ = assertNoDiagnostics()
      // Navigate from unqualified Java type to proto message via package fallback
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/api/Consumer.java",
        "public void consume(SimpleMess@@age msg) {}",
        """|a/src/main/proto/simple.proto:4:9: definition
           |message SimpleMessage {
           |        ^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto-java-multiple-files") {
    // With java_multiple_files=true, each message/enum gets its own class
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/multi.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Request {
           |  string query = 1;
           |}
           |message Response {
           |  string result = 1;
           |}
           |enum ErrorCode {
           |  NONE = 0;
           |  NOT_FOUND = 1;
           |}
           |/a/src/main/java/com/example/Api.java
           |package com.example;
           |import com.example.api.jproto.Request;
           |import com.example.api.jproto.Response;
           |import com.example.api.jproto.ErrorCode;
           |public class Api {
           |  public Response call(Request req) { return null; }
           |  public ErrorCode getError() { return null; }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/multi.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Api.java")
      // All three imports should resolve to the same proto file
      _ <- server.didFocus("a/src/main/java/com/example/Api.java")
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Api.java",
        "import com.example.api.jproto.Requ@@est;",
        """|a/src/main/proto/multi.proto:5:9: definition
           |message Request {
           |        ^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Api.java",
        "import com.example.api.jproto.Respo@@nse;",
        """|a/src/main/proto/multi.proto:8:9: definition
           |message Response {
           |        ^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/Api.java",
        "import com.example.api.jproto.ErrorCo@@de;",
        """|a/src/main/proto/multi.proto:11:6: definition
           |enum ErrorCode {
           |     ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // ========================================================================
  // Builder setter/getter tests - navigate from Java method calls to proto fields
  // ========================================================================

  test("builder-setter-scalar-field") {
    // Test: Message.newBuilder().setFieldName(value) -> proto field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/user.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message User {
           |  string name = 1;
           |  int32 age = 2;
           |  bool active = 3;
           |}
           |/a/src/main/java/com/example/UserBuilder.java
           |package com.example;
           |import com.example.api.jproto.User;
           |public class UserBuilder {
           |  public User build() {
           |    return User.newBuilder()
           |      .setName("Alice")
           |      .setAge(30)
           |      .setActive(true)
           |      .build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      _ <- server.didOpen("a/src/main/java/com/example/UserBuilder.java")
      _ <- server.didFocus("a/src/main/java/com/example/UserBuilder.java")
      _ = assertNoDiagnostics()
      // Navigate from setName to the name field in proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/UserBuilder.java",
        ".setNa@@me(\"Alice\")",
        """|a/src/main/proto/user.proto:6:10: definition
           |  string name = 1;
           |         ^^^^
           |""".stripMargin,
      )
      // Navigate from setAge to the age field in proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/UserBuilder.java",
        ".setA@@ge(30)",
        """|a/src/main/proto/user.proto:7:9: definition
           |  int32 age = 2;
           |        ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("builder-setter-message-field") {
    // Test: setNestedMessage(NestedMessage.newBuilder()...) -> proto field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/principal.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Principal {
           |  int64 id = 1;
           |  string name = 2;
           |}
           |message Context {
           |  Principal authorizing_principal = 1;
           |  Principal authenticating_principal = 2;
           |}
           |/a/src/main/java/com/example/ContextBuilder.java
           |package com.example;
           |import com.example.api.jproto.Context;
           |import com.example.api.jproto.Principal;
           |public class ContextBuilder {
           |  public Context build() {
           |    return Context.newBuilder()
           |      .setAuthorizingPrincipal(
           |          Principal.newBuilder().setId(123L).setName("user").build())
           |      .setAuthenticatingPrincipal(
           |          Principal.newBuilder().setId(456L).build())
           |      .build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/principal.proto")
      _ <- server.didOpen("a/src/main/java/com/example/ContextBuilder.java")
      _ <- server.didFocus("a/src/main/java/com/example/ContextBuilder.java")
      _ = assertNoDiagnostics()
      // Navigate from setAuthorizingPrincipal to authorizing_principal field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ContextBuilder.java",
        ".setAuthorizingPrinci@@pal(",
        """|a/src/main/proto/principal.proto:10:13: definition
           |  Principal authorizing_principal = 1;
           |            ^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from setId on nested builder to id field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ContextBuilder.java",
        ".setI@@d(123L)",
        """|a/src/main/proto/principal.proto:6:9: definition
           |  int64 id = 1;
           |        ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("getter-methods") {
    // Test: message.getFieldName() -> proto field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/person.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Person {
           |  string first_name = 1;
           |  string last_name = 2;
           |  int32 birth_year = 3;
           |}
           |/a/src/main/java/com/example/PersonReader.java
           |package com.example;
           |import com.example.api.jproto.Person;
           |public class PersonReader {
           |  public String fullName(Person p) {
           |    return p.getFirstName() + " " + p.getLastName();
           |  }
           |  public int age(Person p) {
           |    return 2024 - p.getBirthYear();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/person.proto")
      _ <- server.didOpen("a/src/main/java/com/example/PersonReader.java")
      _ <- server.didFocus("a/src/main/java/com/example/PersonReader.java")
      _ = assertNoDiagnostics()
      // Navigate from getFirstName to first_name field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/PersonReader.java",
        "p.getFirstNa@@me()",
        """|a/src/main/proto/person.proto:6:10: definition
           |  string first_name = 1;
           |         ^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from getLastName to last_name field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/PersonReader.java",
        "p.getLastNa@@me()",
        """|a/src/main/proto/person.proto:7:10: definition
           |  string last_name = 2;
           |         ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("repeated-field-methods") {
    // Test: addXxx, getXxxList, getXxxCount, etc. -> proto repeated field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/order.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Order {
           |  repeated string items = 1;
           |  repeated int32 quantities = 2;
           |}
           |/a/src/main/java/com/example/OrderHandler.java
           |package com.example;
           |import com.example.api.jproto.Order;
           |import java.util.List;
           |public class OrderHandler {
           |  public Order createOrder() {
           |    return Order.newBuilder()
           |      .addItems("apple")
           |      .addItems("banana")
           |      .addQuantities(5)
           |      .build();
           |  }
           |  public List<String> getItems(Order o) {
           |    return o.getItemsList();
           |  }
           |  public int itemCount(Order o) {
           |    return o.getItemsCount();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/order.proto")
      _ <- server.didOpen("a/src/main/java/com/example/OrderHandler.java")
      _ <- server.didFocus("a/src/main/java/com/example/OrderHandler.java")
      _ = assertNoDiagnostics()
      // Navigate from addItems to items field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/OrderHandler.java",
        ".addIte@@ms(\"apple\")",
        """|a/src/main/proto/order.proto:6:19: definition
           |  repeated string items = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
      // Navigate from getItemsList to items field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/OrderHandler.java",
        "o.getItemsLi@@st()",
        """|a/src/main/proto/order.proto:6:19: definition
           |  repeated string items = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
      // Navigate from getItemsCount to items field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/OrderHandler.java",
        "o.getItemsCou@@nt()",
        """|a/src/main/proto/order.proto:6:19: definition
           |  repeated string items = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("map-field-methods") {
    // Test: putXxx, getXxxMap, containsXxx, etc. -> proto map field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/config.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Config {
           |  map<string, string> properties = 1;
           |  map<string, int32> counts = 2;
           |}
           |/a/src/main/java/com/example/ConfigHandler.java
           |package com.example;
           |import com.example.api.jproto.Config;
           |import java.util.Map;
           |public class ConfigHandler {
           |  public Config createConfig() {
           |    return Config.newBuilder()
           |      .putProperties("key", "value")
           |      .putCounts("items", 42)
           |      .build();
           |  }
           |  public Map<String, String> getProps(Config c) {
           |    return c.getPropertiesMap();
           |  }
           |  public boolean hasKey(Config c, String key) {
           |    return c.containsProperties(key);
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/config.proto")
      _ <- server.didOpen("a/src/main/java/com/example/ConfigHandler.java")
      _ <- server.didFocus("a/src/main/java/com/example/ConfigHandler.java")
      _ = assertNoDiagnostics()
      // Navigate from putProperties to properties field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ConfigHandler.java",
        ".putProperti@@es(\"key\", \"value\")",
        """|a/src/main/proto/config.proto:6:23: definition
           |  map<string, string> properties = 1;
           |                      ^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from getPropertiesMap to properties field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ConfigHandler.java",
        "c.getPropertiesMa@@p()",
        """|a/src/main/proto/config.proto:6:23: definition
           |  map<string, string> properties = 1;
           |                      ^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from containsProperties to properties field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ConfigHandler.java",
        "c.containsProperti@@es(key)",
        """|a/src/main/proto/config.proto:6:23: definition
           |  map<string, string> properties = 1;
           |                      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("enum-value-access") {
    // Test: EnumName.VALUE -> proto enum value
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/status.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |enum Status {
           |  UNKNOWN = 0;
           |  PENDING = 1;
           |  APPROVED = 2;
           |  REJECTED = 3;
           |}
           |/a/src/main/java/com/example/StatusChecker.java
           |package com.example;
           |import com.example.api.jproto.Status;
           |public class StatusChecker {
           |  public boolean isPending(Status s) {
           |    return s == Status.PENDING;
           |  }
           |  public boolean isApproved(Status s) {
           |    return s == Status.APPROVED;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/status.proto")
      _ <- server.didOpen("a/src/main/java/com/example/StatusChecker.java")
      _ <- server.didFocus("a/src/main/java/com/example/StatusChecker.java")
      _ = assertNoDiagnostics()
      // Navigate from Status.PENDING to PENDING enum value
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/StatusChecker.java",
        "Status.PENDI@@NG",
        """|a/src/main/proto/status.proto:7:3: definition
           |  PENDING = 1;
           |  ^^^^^^^
           |""".stripMargin,
      )
      // Navigate from Status.APPROVED to APPROVED enum value
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/StatusChecker.java",
        "Status.APPROV@@ED",
        """|a/src/main/proto/status.proto:8:3: definition
           |  APPROVED = 2;
           |  ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("has-and-clear-methods") {
    // Test: hasXxx() and clearXxx() -> proto field (proto2 style or message fields)
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/optional.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Address {
           |  string street = 1;
           |}
           |message Contact {
           |  optional string email = 1;
           |  Address address = 2;
           |}
           |/a/src/main/java/com/example/ContactHandler.java
           |package com.example;
           |import com.example.api.jproto.Contact;
           |public class ContactHandler {
           |  public boolean hasEmail(Contact c) {
           |    return c.hasEmail();
           |  }
           |  public boolean hasAddress(Contact c) {
           |    return c.hasAddress();
           |  }
           |  public Contact clearEmail(Contact c) {
           |    return c.toBuilder().clearEmail().build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/optional.proto")
      _ <- server.didOpen("a/src/main/java/com/example/ContactHandler.java")
      _ <- server.didFocus("a/src/main/java/com/example/ContactHandler.java")
      _ = assertNoDiagnostics()
      // Navigate from hasEmail to email field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ContactHandler.java",
        "c.hasEma@@il()",
        """|a/src/main/proto/optional.proto:9:19: definition
           |  optional string email = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
      // Navigate from hasAddress to address field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ContactHandler.java",
        "c.hasAddre@@ss()",
        """|a/src/main/proto/optional.proto:10:11: definition
           |  Address address = 2;
           |          ^^^^^^^
           |""".stripMargin,
      )
      // Navigate from clearEmail to email field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/ContactHandler.java",
        ".clearEma@@il()",
        """|a/src/main/proto/optional.proto:9:19: definition
           |  optional string email = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("same-field-name-multiple-messages") {
    // Test: Multiple messages with same field name should navigate to correct one
    // Bug: setMessage on EchoRequest.Builder was navigating to ForwardDelayedEchoRequest.message
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/echo.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message ForwardDelayedEchoRequest {
           |  optional string message = 1;
           |}
           |message EchoRequest {
           |  optional string message = 1;
           |}
           |message AnotherMessage {
           |  optional string message = 1;
           |}
           |/a/src/main/java/com/example/EchoClient.java
           |package com.example;
           |import com.example.api.jproto.EchoRequest;
           |import com.example.api.jproto.ForwardDelayedEchoRequest;
           |public class EchoClient {
           |  public EchoRequest createEchoRequest(String text) {
           |    return EchoRequest.newBuilder().setMessage(text).build();
           |  }
           |  public ForwardDelayedEchoRequest createForwardedRequest(String text) {
           |    return ForwardDelayedEchoRequest.newBuilder().setMessage(text).build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/echo.proto")
      _ <- server.didOpen("a/src/main/java/com/example/EchoClient.java")
      _ <- server.didFocus("a/src/main/java/com/example/EchoClient.java")
      _ = assertNoDiagnostics()
      // setMessage on EchoRequest.Builder should go to EchoRequest.message
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/EchoClient.java",
        "EchoRequest.newBuilder().setMess@@age(text)",
        """|a/src/main/proto/echo.proto:9:19: definition
           |  optional string message = 1;
           |                  ^^^^^^^
           |""".stripMargin,
      )
      // setMessage on ForwardDelayedEchoRequest.Builder should go to ForwardDelayedEchoRequest.message
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/EchoClient.java",
        "ForwardDelayedEchoRequest.newBuilder().setMess@@age(text)",
        """|a/src/main/proto/echo.proto:6:19: definition
           |  optional string message = 1;
           |                  ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("oneof-field-methods") {
    // Regression test for PLAT-154011: Go to Definition not working for
    // Java->Protobuf on oneof fields.
    // JavaOutlineGenerator was missing getXxx()/hasXxx() on the MESSAGE class
    // for fields inside a oneof block (only the Builder had setters).
    // Test: getXxx(), hasXxx(), setXxx() for oneof fields -> proto oneof field
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/event.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Event {
           |  string id = 1;
           |  oneof payload {
           |    string text_payload = 2;
           |    bytes binary_payload = 3;
           |    int64 numeric_payload = 4;
           |  }
           |}
           |/a/src/main/java/com/example/EventHandler.java
           |package com.example;
           |import com.example.api.jproto.Event;
           |public class EventHandler {
           |  public String getText(Event e) {
           |    if (e.hasTextPayload()) {
           |      return e.getTextPayload();
           |    }
           |    return null;
           |  }
           |  public Event withText(String text) {
           |    return Event.newBuilder()
           |      .setTextPayload(text)
           |      .build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/event.proto")
      _ <- server.didOpen("a/src/main/java/com/example/EventHandler.java")
      _ <- server.didFocus("a/src/main/java/com/example/EventHandler.java")
      _ = assertNoDiagnostics()
      // Navigate from hasTextPayload to text_payload field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/EventHandler.java",
        "e.hasTextPaylo@@ad()",
        """|a/src/main/proto/event.proto:8:12: definition
           |    string text_payload = 2;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from getTextPayload to text_payload field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/EventHandler.java",
        "e.getTextPaylo@@ad()",
        """|a/src/main/proto/event.proto:8:12: definition
           |    string text_payload = 2;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
      // Navigate from setTextPayload to text_payload field
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/EventHandler.java",
        ".setTextPaylo@@ad(text)",
        """|a/src/main/proto/event.proto:8:12: definition
           |    string text_payload = 2;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // ========================================================================
  // Comprehensive field-type coverage test
  // ========================================================================
  //
  // This test is the systematic guard against the class of bugs where
  //   (a) JavaOutlineGenerator is missing a method on the message class / builder, OR
  //   (b) ProtoMtagsV2 is missing the corresponding symbol,
  // either of which silently breaks goto-definition for that method variant.
  //
  // For every (field-type × accessor-kind) combination we verify the full
  // round-trip: Java call site -> proto source location.
  test("all-field-type-goto-definition") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/comprehensive.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Address {
           |  string street = 1;
           |}
           |message Item {
           |  string label = 1;
           |}
           |message Comprehensive {
           |  // scalar field
           |  string title = 1;
           |  // message field
           |  Address address = 2;
           |  // repeated scalar
           |  repeated string tags = 3;
           |  // repeated message
           |  repeated Item items = 4;
           |  // map field
           |  map<string, int32> counts = 5;
           |  // oneof with scalar and message fields
           |  oneof payload {
           |    string text_payload = 6;
           |    Address location_payload = 7;
           |  }
           |  // optional field (proto3 explicit optional)
           |  optional string note = 8;
           |}
           |/a/src/main/java/com/example/AllFieldTypes.java
           |package com.example;
           |import com.example.api.jproto.Comprehensive;
           |import com.example.api.jproto.Address;
           |import com.example.api.jproto.Item;
           |import java.util.List;
           |import java.util.Map;
           |public class AllFieldTypes {
           |  // --- scalar getter/setter ---
           |  public String getTitle(Comprehensive m)     { return m.getTitle(); }
           |  public Comprehensive setTitle(Comprehensive.Builder b) { return b.setTitle("x").build(); }
           |  public Comprehensive clearTitle(Comprehensive.Builder b) { return b.clearTitle().build(); }
           |  // --- message field getter/setter ---
           |  public Address getAddress(Comprehensive m)  { return m.getAddress(); }
           |  public boolean hasAddress(Comprehensive m)  { return m.hasAddress(); }
           |  public Comprehensive setAddress(Comprehensive.Builder b) { return b.setAddress(Address.newBuilder().build()).build(); }
           |  // --- repeated scalar ---
           |  public List<String> getTags(Comprehensive m) { return m.getTagsList(); }
           |  public int getTagsCount(Comprehensive m)    { return m.getTagsCount(); }
           |  public Comprehensive addTag(Comprehensive.Builder b) { return b.addTags("t").build(); }
           |  // --- repeated message ---
           |  public List<Item> getItems(Comprehensive m) { return m.getItemsList(); }
           |  public Comprehensive addItem(Comprehensive.Builder b) { return b.addItems(Item.newBuilder().build()).build(); }
           |  // --- map field ---
           |  public Map<String,Integer> getCounts(Comprehensive m) { return m.getCountsMap(); }
           |  public boolean hasCounts(Comprehensive m, String k)   { return m.containsCounts(k); }
           |  public Comprehensive putCount(Comprehensive.Builder b) { return b.putCounts("k",1).build(); }
           |  // --- oneof scalar getter/setter ---
           |  public String getText(Comprehensive m)      { return m.getTextPayload(); }
           |  public boolean hasText(Comprehensive m)     { return m.hasTextPayload(); }
           |  public Comprehensive setText(Comprehensive.Builder b) { return b.setTextPayload("t").build(); }
           |  // --- oneof message getter/setter ---
           |  public Address getLoc(Comprehensive m)       { return m.getLocationPayload(); }
           |  public boolean hasLoc(Comprehensive m)       { return m.hasLocationPayload(); }
           |  public Comprehensive setLoc(Comprehensive.Builder b) { return b.setLocationPayload(Address.newBuilder().build()).build(); }
           |  // --- optional field ---
           |  public String getNote(Comprehensive m)      { return m.getNote(); }
           |  public boolean hasNote(Comprehensive m)     { return m.hasNote(); }
           |  public Comprehensive setNote(Comprehensive.Builder b) { return b.setNote("n").build(); }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/comprehensive.proto")
      _ <- server.didOpen("a/src/main/java/com/example/AllFieldTypes.java")
      _ <- server.didFocus("a/src/main/java/com/example/AllFieldTypes.java")
      _ = assertNoDiagnostics()
      // -- scalar (line 13) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getTitl@@e()",
        """|a/src/main/proto/comprehensive.proto:13:10: definition
           |  string title = 1;
           |         ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.setTitl@@e(\"x\")",
        """|a/src/main/proto/comprehensive.proto:13:10: definition
           |  string title = 1;
           |         ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.clearTitl@@e()",
        """|a/src/main/proto/comprehensive.proto:13:10: definition
           |  string title = 1;
           |         ^^^^^
           |""".stripMargin,
      )
      // -- message field (line 15) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getAddre@@ss()",
        """|a/src/main/proto/comprehensive.proto:15:11: definition
           |  Address address = 2;
           |          ^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.hasAddre@@ss()",
        """|a/src/main/proto/comprehensive.proto:15:11: definition
           |  Address address = 2;
           |          ^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.setAddre@@ss(",
        """|a/src/main/proto/comprehensive.proto:15:11: definition
           |  Address address = 2;
           |          ^^^^^^^
           |""".stripMargin,
      )
      // -- repeated scalar (line 17) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getTagsLi@@st()",
        """|a/src/main/proto/comprehensive.proto:17:19: definition
           |  repeated string tags = 3;
           |                  ^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getTagsCou@@nt()",
        """|a/src/main/proto/comprehensive.proto:17:19: definition
           |  repeated string tags = 3;
           |                  ^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.addTa@@gs(\"t\")",
        """|a/src/main/proto/comprehensive.proto:17:19: definition
           |  repeated string tags = 3;
           |                  ^^^^
           |""".stripMargin,
      )
      // -- repeated message (line 19) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getItemsLi@@st()",
        """|a/src/main/proto/comprehensive.proto:19:17: definition
           |  repeated Item items = 4;
           |                ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.addIte@@ms(",
        """|a/src/main/proto/comprehensive.proto:19:17: definition
           |  repeated Item items = 4;
           |                ^^^^^
           |""".stripMargin,
      )
      // -- map (line 21) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getCountsMa@@p()",
        """|a/src/main/proto/comprehensive.proto:21:22: definition
           |  map<string, int32> counts = 5;
           |                     ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.containsCoun@@ts(k)",
        """|a/src/main/proto/comprehensive.proto:21:22: definition
           |  map<string, int32> counts = 5;
           |                     ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.putCoun@@ts(\"k\",1)",
        """|a/src/main/proto/comprehensive.proto:21:22: definition
           |  map<string, int32> counts = 5;
           |                     ^^^^^^
           |""".stripMargin,
      )
      // -- oneof scalar (line 24, PLAT-154011) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getTextPaylo@@ad()",
        """|a/src/main/proto/comprehensive.proto:24:12: definition
           |    string text_payload = 6;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.hasTextPaylo@@ad()",
        """|a/src/main/proto/comprehensive.proto:24:12: definition
           |    string text_payload = 6;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.setTextPaylo@@ad(\"t\")",
        """|a/src/main/proto/comprehensive.proto:24:12: definition
           |    string text_payload = 6;
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )
      // -- oneof message (line 25, PLAT-154011) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getLocationPaylo@@ad()",
        """|a/src/main/proto/comprehensive.proto:25:13: definition
           |    Address location_payload = 7;
           |            ^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.hasLocationPaylo@@ad()",
        """|a/src/main/proto/comprehensive.proto:25:13: definition
           |    Address location_payload = 7;
           |            ^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.setLocationPaylo@@ad(",
        """|a/src/main/proto/comprehensive.proto:25:13: definition
           |    Address location_payload = 7;
           |            ^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      // -- optional (line 28) --
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.getNot@@e()",
        """|a/src/main/proto/comprehensive.proto:28:19: definition
           |  optional string note = 8;
           |                  ^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "m.hasNot@@e()",
        """|a/src/main/proto/comprehensive.proto:28:19: definition
           |  optional string note = 8;
           |                  ^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/AllFieldTypes.java",
        "b.setNot@@e(\"n\")",
        """|a/src/main/proto/comprehensive.proto:28:19: definition
           |  optional string note = 8;
           |                  ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // ========================================================================
  // gRPC Service and RPC method tests
  // ========================================================================

  // Regression test for PLAT-154012: Go to Definition not working for the
  // XxxGrpc outer class import.
  // Java code imports e.g. `CompatibilityServiceGrpc` (protoc-java's outer
  // class name), but ProtoMtagsV2 emits `CompatibilityService#` (the bare
  // service name from the .proto).  findProtoClassFromJavaSymbol was doing
  // an exact name match and missing the "Grpc"-suffixed case.
  test("grpc-outer-class-goto-definition") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/greeter.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message HelloRequest {
           |  string name = 1;
           |}
           |message HelloReply {
           |  string message = 1;
           |}
           |service Greeter {
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |}
           |/a/src/main/java/com/example/GreeterService.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |public class GreeterService {
           |  public static Class<?> grpcClass() { return GreeterGrpc.class; }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterService.java")
      _ <- server.didFocus("a/src/main/java/com/example/GreeterService.java")
      _ = assertNoDiagnostics()
      // Navigate from the GreeterGrpc import to the Greeter service in the proto
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/GreeterService.java",
        "import com.example.api.jproto.GreeterGr@@pc;",
        """|a/src/main/proto/greeter.proto:11:9: definition
           |service Greeter {
           |        ^^^^^^^
           |""".stripMargin,
      )
      // Also navigate from GreeterGrpc used as a qualifier in a method body
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/GreeterService.java",
        "return GreeterGr@@pc.class;",
        """|a/src/main/proto/greeter.proto:11:9: definition
           |service Greeter {
           |        ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // Regression test for PLAT-154771: we generate a standalone top-level
  // GreeterImplBase for java_multiple_files=true, and it must stay assignment-
  // compatible with the nested GreeterGrpc.GreeterImplBase type.
  test("service-implbase-compatible-with-grpc-nested-type") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/greeter.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message HelloRequest {
           |  string name = 1;
           |}
           |message HelloReply {
           |  string message = 1;
           |}
           |service Greeter {
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |}
           |/a/src/main/java/com/example/GreeterCompat.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.GreeterImplBase;
           |public class GreeterCompat {
           |  public GreeterGrpc.GreeterImplBase upcast(GreeterImplBase impl) {
           |    return impl;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterCompat.java")
      _ <- server.didFocus("a/src/main/java/com/example/GreeterCompat.java")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("rpc-stub-method-goto-definition") {
    // Test: stub.sayHello(request) -> rpc SayHello in proto service
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/greeter.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message HelloRequest {
           |  string name = 1;
           |}
           |message HelloReply {
           |  string message = 1;
           |}
           |service Greeter {
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |}
           |/a/src/main/java/com/example/GreeterClient.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |public class GreeterClient {
           |  private final GreeterGrpc.GreeterBlockingStub stub;
           |  public GreeterClient(GreeterGrpc.GreeterBlockingStub stub) {
           |    this.stub = stub;
           |  }
           |  public HelloReply greet(String name) {
           |    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
           |    return stub.sayHello(request);
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterClient.java")
      _ <- server.didFocus("a/src/main/java/com/example/GreeterClient.java")
      _ = assertNoDiagnostics()
      // Navigate from stub.sayHello to rpc SayHello definition (line 12 is 0-based, so line 13 in 1-based)
      _ <- server.assertDefinition(
        "a/src/main/java/com/example/GreeterClient.java",
        "stub.sayHel@@lo(request)",
        """|a/src/main/proto/greeter.proto:12:7: definition
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |      ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // ========================================================================
  // Interactive update tests - proto changes should trigger Java re-diagnosis
  // ========================================================================

  test("proto-rename-invalidates-java") {
    // Test: When a proto message is renamed and saved, then we focus on the Java file,
    // it should show errors because the Java PC was restarted
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/model.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message User {
           |  string name = 1;
           |}
           |/a/src/main/java/com/example/Service.java
           |package com.example;
           |import com.example.api.jproto.User;
           |public class Service {
           |  public void process(User user) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/model.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Service.java")
      _ <- server.didFocus("a/src/main/java/com/example/Service.java")
      // Initially no errors - User class is found from proto
      _ = assertNoDiagnostics()

      // Rename User to Customer in the proto file and save
      _ <- server.didChange("a/src/main/proto/model.proto") { _ =>
        """|syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Customer {
           |  string name = 1;
           |}
           |""".stripMargin
      }
      _ <- server.didSave("a/src/main/proto/model.proto")

      // Focus on the Java file - this triggers Java PC restart since proto was saved
      _ <- server.didFocus("a/src/main/java/com/example/Service.java")

      // Java file should now have an error - User class no longer exists
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/com/example/Service.java:2:30: error: cannot find symbol
           |  symbol:   class User
           |  location: package com.example.api.jproto
           |import com.example.api.jproto.User;
           |                             ^^^^^
           |a/src/main/java/com/example/Service.java:4:23: error: cannot find symbol
           |  symbol:   class User
           |  location: class com.example.Service
           |  public void process(User user) {}
           |                      ^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
