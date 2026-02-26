package scala.meta.internal.builds.bazelnative

import java.io.InputStream
import java.util.concurrent.TimeUnit

import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver

/**
 * gRPC server implementing the Bazel Build Event Service (BES).
 *
 * Bazel is launched with `--bes_backend=grpc://localhost:<port>` and streams
 * build events to this server via the `PublishBuildToolEventStream` RPC.
 *
 * Since the BES proto stubs are not published as standalone Maven artifacts,
 * we define the service using raw gRPC APIs and parse the protobuf messages
 * generically.
 */
class BazelNativeBesServer(translator: BazelNativeBepTranslator) {

  @volatile private var server: Server = _
  @volatile private var boundPort: Int = -1

  def port: Int = boundPort

  def start(): Int = {
    val service = buildServiceDefinition()
    server = ServerBuilder
      .forPort(0)
      .addService(service)
      .build()
      .start()

    boundPort = server.getPort
    scribe.debug(
      s"[BazelNative BES] gRPC server started on port $boundPort"
    )
    boundPort
  }

  def shutdown(): Unit = {
    if (server != null) {
      scribe.debug(s"[BazelNative BES] Shutting down gRPC server")
      server.shutdown()
      try {
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          server.shutdownNow()
          server.awaitTermination(2, TimeUnit.SECONDS)
        }
      } catch {
        case _: InterruptedException =>
          server.shutdownNow()
      }
    }
  }

  def isRunning: Boolean = server != null && !server.isShutdown

  private val passthrough: MethodDescriptor.Marshaller[Array[Byte]] =
    new MethodDescriptor.Marshaller[Array[Byte]] {
      override def stream(value: Array[Byte]): InputStream =
        new java.io.ByteArrayInputStream(value)
      override def parse(stream: InputStream): Array[Byte] =
        stream.readAllBytes()
    }

  private def buildServiceDefinition(): ServerServiceDefinition = {
    val publishLifecycleEvent = MethodDescriptor
      .newBuilder(passthrough, passthrough)
      .setType(MethodType.UNARY)
      .setFullMethodName(
        "google.devtools.build.v1.PublishBuildEvent/PublishLifecycleEvent"
      )
      .build()

    val publishBuildToolEventStream = MethodDescriptor
      .newBuilder(passthrough, passthrough)
      .setType(MethodType.BIDI_STREAMING)
      .setFullMethodName(
        "google.devtools.build.v1.PublishBuildEvent/PublishBuildToolEventStream"
      )
      .build()

    val lifecycleHandler: ServerCallHandler[Array[Byte], Array[Byte]] =
      ServerCalls.asyncUnaryCall(
        (
            request: Array[Byte],
            responseObserver: StreamObserver[Array[Byte]],
        ) => {
          scribe.debug(
            s"[BazelNative BES] Lifecycle event received (${request.length} bytes)"
          )
          translator.onRawLifecycleEvent(request)
          // Respond with empty proto (google.protobuf.Empty)
          responseObserver.onNext(Array.emptyByteArray)
          responseObserver.onCompleted()
        }
      )

    val streamHandler: ServerCallHandler[Array[Byte], Array[Byte]] =
      ServerCalls.asyncBidiStreamingCall(
        (
          responseObserver: StreamObserver[Array[Byte]]
        ) => {
          scribe.debug(
            s"[BazelNative BES] Build tool event stream opened"
          )
          new StreamObserver[Array[Byte]] {
            override def onNext(request: Array[Byte]): Unit = {
              scribe.debug(
                s"[BazelNative BES] Received stream event (${request.length} bytes)"
              )
              val response = translator.onRawStreamEvent(request)
              responseObserver.onNext(response)
            }

            override def onError(t: Throwable): Unit = {
              scribe.debug(
                s"[BazelNative BES] Stream error: ${t.getMessage}"
              )
            }

            override def onCompleted(): Unit = {
              scribe.debug(
                s"[BazelNative BES] Build tool event stream completed"
              )
              responseObserver.onCompleted()
            }
          }
        }
      )

    ServerServiceDefinition
      .builder("google.devtools.build.v1.PublishBuildEvent")
      .addMethod(publishLifecycleEvent, lifecycleHandler)
      .addMethod(publishBuildToolEventStream, streamHandler)
      .build()
  }
}
