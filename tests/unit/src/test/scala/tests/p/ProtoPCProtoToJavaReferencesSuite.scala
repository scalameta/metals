package tests.p

/**
 * Tests for Proto-to-Java find references.
 *
 * When you do "Find References" on a proto symbol (message, field, enum),
 * it should include usages in Java files that import the proto-generated classes.
 */
class ProtoPCProtoToJavaReferencesSuite
    extends BaseProtoPCSuite("proto-pc-proto-to-java-refs") {

  test("message-class-import") {
    // Find-refs on proto message should include Java import
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
           |}
           |/a/src/main/java/com/example/Service.java
           |package com.example;
           |import com.example.api.jproto.User;
           |public class Service {
           |  public void process(User user) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Service.java")
      // Proto definition + Java import + Java parameter type
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/user.proto",
        "message Us@@er {",
        """|a/src/main/java/com/example/Service.java:2:31: reference
           |import com.example.api.jproto.User;
           |                              ^^^^
           |a/src/main/java/com/example/Service.java:4:23: reference
           |  public void process(User user) {}
           |                      ^^^^
           |a/src/main/proto/user.proto:5:9: reference
           |message User {
           |        ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("field-getter-references") {
    // Find-refs on proto field should include Java getXxx() calls
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
           |}
           |/a/src/main/java/com/example/PersonReader.java
           |package com.example;
           |import com.example.api.jproto.Person;
           |public class PersonReader {
           |  public String read(Person p) {
           |    return p.getFirstName();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/person.proto")
      _ <- server.didOpen("a/src/main/java/com/example/PersonReader.java")
      // Proto field definition + Java getter call
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/person.proto",
        "string first@@_name",
        """|a/src/main/java/com/example/PersonReader.java:5:14: reference
           |    return p.getFirstName();
           |             ^^^^^^^^^^^^
           |a/src/main/proto/person.proto:6:10: reference
           |  string first_name = 1;
           |         ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("field-setter-references") {
    // Find-refs on proto field should include Java setXxx() calls
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
           |}
           |/a/src/main/java/com/example/UserBuilder.java
           |package com.example;
           |import com.example.api.jproto.User;
           |public class UserBuilder {
           |  public User build(String name) {
           |    return User.newBuilder().setName(name).build();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      _ <- server.didOpen("a/src/main/java/com/example/UserBuilder.java")
      // Proto field definition + Java setter call
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/user.proto",
        "string na@@me =",
        """|a/src/main/java/com/example/UserBuilder.java:5:30: reference
           |    return User.newBuilder().setName(name).build();
           |                             ^^^^^^^
           |a/src/main/proto/user.proto:6:10: reference
           |  string name = 1;
           |         ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("enum-value-references") {
    // Find-refs on proto enum value should include Java usages
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
           |}
           |/a/src/main/java/com/example/StatusChecker.java
           |package com.example;
           |import com.example.api.jproto.Status;
           |public class StatusChecker {
           |  public boolean isPending(Status s) {
           |    return s == Status.PENDING;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/status.proto")
      _ <- server.didOpen("a/src/main/java/com/example/StatusChecker.java")
      // Proto enum value definition + Java usage
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/status.proto",
        "PEND@@ING = 1",
        """|a/src/main/java/com/example/StatusChecker.java:5:24: reference
           |    return s == Status.PENDING;
           |                       ^^^^^^^
           |a/src/main/proto/status.proto:7:3: reference
           |  PENDING = 1;
           |  ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("repeated-field-references") {
    // Find-refs on repeated field should include addXxx, getXxxList calls
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
           |}
           |/a/src/main/java/com/example/OrderHandler.java
           |package com.example;
           |import com.example.api.jproto.Order;
           |import java.util.List;
           |public class OrderHandler {
           |  public Order create() {
           |    return Order.newBuilder().addItems("apple").build();
           |  }
           |  public List<String> getItems(Order o) {
           |    return o.getItemsList();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/order.proto")
      _ <- server.didOpen("a/src/main/java/com/example/OrderHandler.java")
      // Proto field + addItems + getItemsList
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/order.proto",
        "string ite@@ms =",
        """|a/src/main/java/com/example/OrderHandler.java:6:31: reference
           |    return Order.newBuilder().addItems("apple").build();
           |                              ^^^^^^^^
           |a/src/main/java/com/example/OrderHandler.java:9:14: reference
           |    return o.getItemsList();
           |             ^^^^^^^^^^^^
           |a/src/main/proto/order.proto:6:19: reference
           |  repeated string items = 1;
           |                  ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("map-field-references") {
    // Find-refs on map field should include putXxx, getXxxMap calls
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
           |}
           |/a/src/main/java/com/example/ConfigHandler.java
           |package com.example;
           |import com.example.api.jproto.Config;
           |import java.util.Map;
           |public class ConfigHandler {
           |  public Config create() {
           |    return Config.newBuilder().putProperties("k", "v").build();
           |  }
           |  public Map<String, String> get(Config c) {
           |    return c.getPropertiesMap();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/config.proto")
      _ <- server.didOpen("a/src/main/java/com/example/ConfigHandler.java")
      // Proto field + Java map methods
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/config.proto",
        "string> prop@@erties =",
        """|a/src/main/java/com/example/ConfigHandler.java:6:32: reference
           |    return Config.newBuilder().putProperties("k", "v").build();
           |                               ^^^^^^^^^^^^^
           |a/src/main/java/com/example/ConfigHandler.java:9:14: reference
           |    return c.getPropertiesMap();
           |             ^^^^^^^^^^^^^^^^
           |a/src/main/proto/config.proto:6:23: reference
           |  map<string, string> properties = 1;
           |                      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto-refs-and-java-refs-combined") {
    // Find-refs should include both proto usages and Java usages
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/common.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message Address {
           |  string street = 1;
           |}
           |/a/src/main/java/com/example/AddressReader.java
           |package com.example;
           |import com.example.api.jproto.Address;
           |public class AddressReader {
           |  public String read(Address a) {
           |    return a.getStreet();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/common.proto")
      _ <- server.didOpen("a/src/main/java/com/example/AddressReader.java")
      // Proto definition + Java import/usage
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/common.proto",
        "message Addr@@ess {",
        """|a/src/main/java/com/example/AddressReader.java:2:31: reference
           |import com.example.api.jproto.Address;
           |                              ^^^^^^^
           |a/src/main/java/com/example/AddressReader.java:4:22: reference
           |  public String read(Address a) {
           |                     ^^^^^^^
           |a/src/main/proto/common.proto:5:9: reference
           |message Address {
           |        ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto2-message-class-import") {
    // Find-refs on proto2 message should include Java import
    // This is a regression test for proto2 support
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/user.proto
           |syntax = "proto2";
           |package users;
           |option java_package = "com.databricks.api.proto.users.jproto";
           |option java_multiple_files = true;
           |message User {
           |  optional string name = 1;
           |}
           |/a/src/main/java/com/example/Service.java
           |package com.example;
           |import com.databricks.api.proto.users.jproto.User;
           |public class Service {
           |  public void process(User user) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      _ <- server.didOpen("a/src/main/java/com/example/Service.java")
      // Proto definition + Java import + Java parameter type
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/user.proto",
        "message Us@@er {",
        """|a/src/main/java/com/example/Service.java:2:46: reference
           |import com.databricks.api.proto.users.jproto.User;
           |                                             ^^^^
           |a/src/main/java/com/example/Service.java:4:23: reference
           |  public void process(User user) {}
           |                      ^^^^
           |a/src/main/proto/user.proto:5:9: reference
           |message User {
           |        ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto-to-proto-qualified-type-references") {
    // Find-refs on proto message should include qualified type usages in other proto files.
    // Regression for semanticdb.proto -> mbt.proto references (e.g. Range).
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/semanticdb.proto
           |syntax = "proto3";
           |package scala.meta.internal.semanticdb;
           |option java_package = "scala.meta.internal.jsemanticdb";
           |message Range {
           |  int32 start_line = 1;
           |}
           |/a/src/main/proto/mbt.proto
           |syntax = "proto3";
           |package scala.meta.internal.mbt;
           |import "semanticdb.proto";
           |message SymbolInformation {
           |  scala.meta.internal.semanticdb.Range definition_range = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/semanticdb.proto")
      _ <- server.didOpen("a/src/main/proto/mbt.proto")
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/semanticdb.proto",
        "message Ran@@ge {",
        """|a/src/main/proto/mbt.proto:5:34: reference
           |  scala.meta.internal.semanticdb.Range definition_range = 1;
           |                                 ^^^^^
           |a/src/main/proto/semanticdb.proto:4:9: reference
           |message Range {
           |        ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto-message-references-single-file-java-outer-class") {
    // Find-refs on proto message should include Java references when java_multiple_files=false
    // and Java symbols are nested under the generated outer class.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/semanticdb.proto
           |syntax = "proto3";
           |package scala.meta.internal.semanticdb;
           |option java_package = "scala.meta.internal.jsemanticdb";
           |option java_outer_classname = "Semanticdb";
           |option java_multiple_files = false;
           |message Signature {
           |  string value = 1;
           |}
           |/a/src/main/java/com/example/SemanticdbConsumer.java
           |package com.example;
           |import scala.meta.internal.jsemanticdb.Semanticdb.Signature;
           |public class SemanticdbConsumer {
           |  public Signature consume(Signature value) {
           |    return value;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/semanticdb.proto")
      _ <- server.didOpen("a/src/main/java/com/example/SemanticdbConsumer.java")
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/semanticdb.proto",
        "message Signat@@ure {",
        """|a/src/main/java/com/example/SemanticdbConsumer.java:2:51: reference
           |import scala.meta.internal.jsemanticdb.Semanticdb.Signature;
           |                                                  ^^^^^^^^^
           |a/src/main/java/com/example/SemanticdbConsumer.java:4:10: reference
           |  public Signature consume(Signature value) {
           |         ^^^^^^^^^
           |a/src/main/java/com/example/SemanticdbConsumer.java:4:28: reference
           |  public Signature consume(Signature value) {
           |                           ^^^^^^^^^
           |a/src/main/proto/semanticdb.proto:6:9: reference
           |message Signature {
           |        ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("proto-message-references-single-file-java-default-outer-class") {
    // Find-refs on proto message should include Java references when
    // java_multiple_files=false and java_outer_classname is omitted.
    // This mirrors semanticdb.proto-style setup where Java uses OuterClass.NestedType.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/semanticdb.proto
           |syntax = "proto3";
           |package scala.meta.internal.semanticdb;
           |option java_package = "scala.meta.internal.jsemanticdb";
           |message Signature {
           |  string value = 1;
           |}
           |/a/src/main/java/com/example/SemanticdbConsumer.java
           |package com.example;
           |import scala.meta.internal.jsemanticdb.Semanticdb.Signature;
           |public class SemanticdbConsumer {
           |  public Signature consume(Signature value) {
           |    return value;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/semanticdb.proto")
      _ <- server.didOpen("a/src/main/java/com/example/SemanticdbConsumer.java")
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/semanticdb.proto",
        "message Signat@@ure {",
        """|a/src/main/java/com/example/SemanticdbConsumer.java:2:51: reference
           |import scala.meta.internal.jsemanticdb.Semanticdb.Signature;
           |                                                  ^^^^^^^^^
           |a/src/main/java/com/example/SemanticdbConsumer.java:4:10: reference
           |  public Signature consume(Signature value) {
           |         ^^^^^^^^^
           |a/src/main/java/com/example/SemanticdbConsumer.java:4:28: reference
           |  public Signature consume(Signature value) {
           |                           ^^^^^^^^^
           |a/src/main/proto/semanticdb.proto:4:9: reference
           |message Signature {
           |        ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("rpc-method-references") {
    // Find-refs on proto RPC should include Java stub method calls and ImplBase overrides
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
      // Find references for rpc SayHello - should include:
      // 1. Java stub method call
      // 2. Proto RPC definition
      // NOTE: Does NOT include stub CLASS references (field type, constructor param) -
      // those should only show for service-level find-refs
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/greeter.proto",
        "rpc SayHel@@lo",
        """|a/src/main/java/com/example/GreeterClient.java:12:17: reference
           |    return stub.sayHello(request);
           |                ^^^^^^^^
           |a/src/main/proto/greeter.proto:12:7: reference
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |      ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("rpc-implbase-override-references") {
    // Find-refs on proto RPC should include Java ImplBase method overrides
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
           |/a/src/main/java/com/example/GreeterImpl.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |import io.grpc.stub.StreamObserver;
           |public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
           |  @Override
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |    HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + request.getName()).build();
           |    responseObserver.onNext(reply);
           |    responseObserver.onCompleted();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterImpl.java")
      // Find references for rpc SayHello - should include:
      // 1. Java method that overrides the RPC (method definition)
      // 2. Proto RPC definition
      // NOTE: Does NOT include the ImplBase class reference - that's only for service find-refs
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/greeter.proto",
        "rpc SayHel@@lo",
        """|a/src/main/java/com/example/GreeterImpl.java:8:15: reference
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |              ^^^^^^^^
           |a/src/main/proto/greeter.proto:12:7: reference
           |  rpc SayHello (HelloRequest) returns (HelloReply);
           |      ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("service-references-include-implbase-class") {
    // Find-refs on proto SERVICE should include ImplBase class references
    // (unlike RPC find-refs which should only show method overrides)
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
           |/a/src/main/java/com/example/GreeterImpl.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |import io.grpc.stub.StreamObserver;
           |public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
           |  @Override
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |    HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + request.getName()).build();
           |    responseObserver.onNext(reply);
           |    responseObserver.onCompleted();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterImpl.java")
      // Find references for service Greeter - should include:
      // 1. Import statement for GreeterGrpc
      // 2. GreeterGrpc outer class reference in extends clause
      // 3. GreeterImplBase nested class reference
      // 4. Proto service definition
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/greeter.proto",
        "service Gree@@ter",
        """|a/src/main/java/com/example/GreeterImpl.java:2:31: reference
           |import com.example.api.jproto.GreeterGrpc;
           |                              ^^^^^^^^^^^
           |a/src/main/java/com/example/GreeterImpl.java:6:34: reference
           |public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
           |                                 ^^^^^^^^^^^
           |a/src/main/java/com/example/GreeterImpl.java:6:46: reference
           |public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
           |                                             ^^^^^^^^^^^^^^^
           |a/src/main/proto/greeter.proto:11:9: reference
           |service Greeter {
           |        ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("service-find-implementations") {
    // Find-implementations on proto SERVICE should find Java classes extending ImplBase
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
           |/a/src/main/java/com/example/GreeterServiceImpl.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |import io.grpc.stub.StreamObserver;
           |public class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {
           |  @Override
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |    HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + request.getName()).build();
           |    responseObserver.onNext(reply);
           |    responseObserver.onCompleted();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterServiceImpl.java")
      // Find implementations for service Greeter - should find the Java class
      _ <- server.assertImplementationsSubquery(
        "a/src/main/proto/greeter.proto",
        "service Gree@@ter",
        """|a/src/main/java/com/example/GreeterServiceImpl.java:6:14: implementation
           |public class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {
           |             ^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("rpc-find-implementations") {
    // Find-implementations on proto RPC should find Java method overrides
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
           |/a/src/main/java/com/example/GreeterServiceImpl.java
           |package com.example;
           |import com.example.api.jproto.GreeterGrpc;
           |import com.example.api.jproto.HelloRequest;
           |import com.example.api.jproto.HelloReply;
           |import io.grpc.stub.StreamObserver;
           |public class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {
           |  @Override
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |    HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + request.getName()).build();
           |    responseObserver.onNext(reply);
           |    responseObserver.onCompleted();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/greeter.proto")
      _ <- server.didOpen("a/src/main/java/com/example/GreeterServiceImpl.java")
      // Find implementations for rpc SayHello - should find the Java method override
      _ <- server.assertImplementationsSubquery(
        "a/src/main/proto/greeter.proto",
        "rpc SayHel@@lo",
        """|a/src/main/java/com/example/GreeterServiceImpl.java:8:15: implementation
           |  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
           |              ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("multiple-rpc-find-implementations") {
    // BUG: Find-implementations on RPC shows ALL rpc implementations in the service
    // instead of only that specific method implementation.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/service.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message EchoRequest {
           |  string message = 1;
           |}
           |message EchoResponse {
           |  string message = 1;
           |}
           |message ErrorRequest {
           |  string message = 1;
           |}
           |message ErrorResponse {
           |  string message = 1;
           |}
           |service TestService {
           |  rpc Echo (EchoRequest) returns (EchoResponse);
           |  rpc ThrowError (ErrorRequest) returns (ErrorResponse);
           |}
           |/a/src/main/java/com/example/TestServiceImpl.java
           |package com.example;
           |import com.example.api.jproto.TestServiceGrpc;
           |import com.example.api.jproto.EchoRequest;
           |import com.example.api.jproto.EchoResponse;
           |import com.example.api.jproto.ErrorRequest;
           |import com.example.api.jproto.ErrorResponse;
           |import io.grpc.stub.StreamObserver;
           |public class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
           |  @Override
           |  public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |    responseObserver.onNext(EchoResponse.newBuilder().build());
           |    responseObserver.onCompleted();
           |  }
           |  @Override
           |  public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |    throw new RuntimeException("Error thrown from server");
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/service.proto")
      _ <- server.didOpen("a/src/main/java/com/example/TestServiceImpl.java")
      // Find implementations for first RPC "Echo" - should ONLY find echo() method
      _ <- server.assertImplementationsSubquery(
        "a/src/main/proto/service.proto",
        "rpc Ech@@o",
        """|a/src/main/java/com/example/TestServiceImpl.java:10:15: implementation
           |  public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |              ^^^^
           |""".stripMargin,
      )
      // Find implementations for second RPC "ThrowError" - should ONLY find throwError() method
      _ <- server.assertImplementationsSubquery(
        "a/src/main/proto/service.proto",
        "rpc Throw@@Error",
        """|a/src/main/java/com/example/TestServiceImpl.java:15:15: implementation
           |  public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |              ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("multiple-rpc-references") {
    // BUG: Find-refs on second RPC in a service doesn't find Java override
    // Find-implementations DOES find it, but find-references does NOT.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/service.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message EchoRequest {
           |  string message = 1;
           |}
           |message EchoResponse {
           |  string message = 1;
           |}
           |message ErrorRequest {
           |  string message = 1;
           |}
           |message ErrorResponse {
           |  string message = 1;
           |}
           |service TestService {
           |  rpc Echo (EchoRequest) returns (EchoResponse);
           |  rpc ThrowError (ErrorRequest) returns (ErrorResponse);
           |}
           |/a/src/main/java/com/example/TestServiceImpl.java
           |package com.example;
           |import com.example.api.jproto.TestServiceGrpc;
           |import com.example.api.jproto.EchoRequest;
           |import com.example.api.jproto.EchoResponse;
           |import com.example.api.jproto.ErrorRequest;
           |import com.example.api.jproto.ErrorResponse;
           |import io.grpc.stub.StreamObserver;
           |public class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
           |  @Override
           |  public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |    EchoResponse response = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
           |    responseObserver.onNext(response);
           |    responseObserver.onCompleted();
           |  }
           |  @Override
           |  public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |    throw new RuntimeException("Error thrown from server");
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/service.proto")
      _ <- server.didOpen("a/src/main/java/com/example/TestServiceImpl.java")
      // Find references for first RPC "Echo" - should include Java override
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/service.proto",
        "rpc Ech@@o",
        """|a/src/main/java/com/example/TestServiceImpl.java:10:15: reference
           |  public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |              ^^^^
           |a/src/main/proto/service.proto:18:7: reference
           |  rpc Echo (EchoRequest) returns (EchoResponse);
           |      ^^^^
           |""".stripMargin,
      )
      // Find references for second RPC "ThrowError" - should ALSO include Java override
      // This is the bug - it doesn't find the Java override
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/service.proto",
        "rpc Throw@@Error",
        """|a/src/main/java/com/example/TestServiceImpl.java:16:15: reference
           |  public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |              ^^^^^^^^^^
           |a/src/main/proto/service.proto:19:7: reference
           |  rpc ThrowError (ErrorRequest) returns (ErrorResponse);
           |      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("anonymous-class-rpc-references") {
    // BUG: Find-refs on RPC doesn't find Java override in ANONYMOUS class
    // This is different from named classes - anonymous classes have local symbols
    // like "local1" in Java SemanticDB.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/service.proto
           |syntax = "proto3";
           |package com.example.api;
           |option java_package = "com.example.api.jproto";
           |option java_multiple_files = true;
           |message EchoRequest {
           |  string message = 1;
           |}
           |message EchoResponse {
           |  string message = 1;
           |}
           |message ErrorRequest {
           |  string message = 1;
           |}
           |message ErrorResponse {
           |  string message = 1;
           |}
           |service TestService {
           |  rpc Echo (EchoRequest) returns (EchoResponse);
           |  rpc ThrowError (ErrorRequest) returns (ErrorResponse);
           |}
           |/a/src/main/java/com/example/TestClient.java
           |package com.example;
           |import com.example.api.jproto.TestServiceGrpc;
           |import com.example.api.jproto.EchoRequest;
           |import com.example.api.jproto.EchoResponse;
           |import com.example.api.jproto.ErrorRequest;
           |import com.example.api.jproto.ErrorResponse;
           |import io.grpc.stub.StreamObserver;
           |public class TestClient {
           |  public TestServiceGrpc.TestServiceImplBase createService() {
           |    return new TestServiceGrpc.TestServiceImplBase() {
           |      @Override
           |      public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |        EchoResponse response = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
           |        responseObserver.onNext(response);
           |        responseObserver.onCompleted();
           |      }
           |      @Override
           |      public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |        throw new RuntimeException("Error thrown from server");
           |      }
           |    };
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/service.proto")
      _ <- server.didOpen("a/src/main/java/com/example/TestClient.java")
      // Find references for first RPC "Echo" - should include Java override in anonymous class
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/service.proto",
        "rpc Ech@@o",
        """|a/src/main/java/com/example/TestClient.java:12:19: reference
           |      public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
           |                  ^^^^
           |a/src/main/proto/service.proto:18:7: reference
           |  rpc Echo (EchoRequest) returns (EchoResponse);
           |      ^^^^
           |""".stripMargin,
      )
      // Find references for second RPC "ThrowError" - should ALSO include Java override
      _ <- server.assertReferencesSubquery(
        "a/src/main/proto/service.proto",
        "rpc Throw@@Error",
        """|a/src/main/java/com/example/TestClient.java:18:19: reference
           |      public void throwError(ErrorRequest request, StreamObserver<ErrorResponse> responseObserver) {
           |                  ^^^^^^^^^^
           |a/src/main/proto/service.proto:19:7: reference
           |  rpc ThrowError (ErrorRequest) returns (ErrorResponse);
           |      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
