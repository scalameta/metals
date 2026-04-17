package tests.p

class ProtoPCDocumentSymbolSuite
    extends BaseProtoPCSuite("proto-pc-document-symbol") {

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |package foo.bar;
           |
           |message Outer {
           |  string id = 1;
           |  message Inner {
           |    string name = 1;
           |  }
           |  enum Status {
           |    UNKNOWN = 0;
           |    OK = 1;
           |  }
           |  oneof choice {
           |    string a = 2;
           |    int32 b = 3;
           |  }
           |}
           |
           |service Greeter {
           |  rpc SayHello(Outer) returns (Outer);
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      symbols <- server.documentSymbols("a/src/main/proto/example.proto")
      _ = assertNoDiff(
        symbols,
        """|syntax = "proto3";
           |/*foo.bar(Package):4*/package foo.bar;
           |
           |/*foo.bar.Outer(Class):19*/message Outer {
           |  /*foo.bar.Outer#id(Field):6*/string id = 1;
           |  /*foo.bar.Outer#Inner(Class):9*/message Inner {
           |    /*foo.bar.Outer#Inner#name(Field):8*/string name = 1;
           |  }
           |  /*foo.bar.Outer#Status(Enum):13*/enum Status {
           |    /*foo.bar.Outer#Status.UNKNOWN(EnumMember):11*/UNKNOWN = 0;
           |    /*foo.bar.Outer#Status.OK(EnumMember):12*/OK = 1;
           |  }
           |  /*foo.bar.Outer#choice(Struct):17*/oneof choice {
           |    /*foo.bar.Outer#choice.a(Field):15*/string a = 2;
           |    /*foo.bar.Outer#choice.b(Field):16*/int32 b = 3;
           |  }
           |}
           |
           |/*foo.bar.Greeter(Interface):22*/service Greeter {
           |  /*foo.bar.Greeter#SayHello(Method):21*/rpc SayHello(Outer) returns (Outer);
           |}""".stripMargin,
      )
    } yield ()
  }
}
