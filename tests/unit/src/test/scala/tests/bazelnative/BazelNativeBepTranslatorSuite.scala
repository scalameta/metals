package tests.bazelnative

import munit.FunSuite

import ch.epfl.scala.bsp4j._

import com.google.gson.Gson
import com.google.gson.JsonObject

import scala.meta.internal.builds.bazelnative.BazelNativeBepTranslator

class BazelNativeBepTranslatorSuite extends FunSuite {

  private val gson = new Gson()

  test("notifyBuildStarted emits TaskStart without targets (fallback)") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.notifyBuildStarted("test-invocation-1")

    val starts = mockClient.allTaskStarts
    assertEquals(starts.size, 1)
    assertEquals(starts.head.getTaskId.getId, "test-invocation-1")
    assert(starts.head.getMessage.contains("started"))
    assertEquals(starts.head.getDataKind, null)
  }

  test("notifyBuildStarted with targets emits COMPILE_TASK per target") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target1 = new BuildTargetIdentifier("test://target1")
    val target2 = new BuildTargetIdentifier("test://target2")

    translator.setTargets(List(target1, target2))
    translator.notifyBuildStarted("inv-1")

    val starts = mockClient.allTaskStarts
    assertEquals(starts.size, 2)
    starts.foreach { s =>
      assertEquals(s.getDataKind, TaskStartDataKind.COMPILE_TASK)
      val compileTask = gson.fromJson(
        gson.toJson(s.getData),
        classOf[CompileTask],
      )
      assert(compileTask.getTarget != null)
    }
    val taskTargets = starts.map { s =>
      gson
        .fromJson(gson.toJson(s.getData), classOf[CompileTask])
        .getTarget
        .getUri
    }
    assert(taskTargets.contains("test://target1"))
    assert(taskTargets.contains("test://target2"))
  }

  test("notifyBuildFinished emits TaskFinish with OK for exit code 0") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.notifyBuildStarted("test-invocation-2")
    translator.notifyBuildFinished("test-invocation-2", 0)

    val finishes = mockClient.allTaskFinishes
    assertEquals(finishes.size, 1)
    assertEquals(finishes.head.getStatus, StatusCode.OK)
    assertEquals(finishes.head.getTaskId.getId, "test-invocation-2")
  }

  test(
    "notifyBuildFinished emits TaskFinish with ERROR for non-zero exit code"
  ) {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.notifyBuildStarted("test-invocation-3")
    translator.notifyBuildFinished("test-invocation-3", 1)

    val finishes = mockClient.allTaskFinishes
    assertEquals(finishes.size, 1)
    assertEquals(finishes.head.getStatus, StatusCode.ERROR)
  }

  test("notifyBuildFinished with targets emits COMPILE_REPORT per target") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target1 = new BuildTargetIdentifier("test://target1")
    val target2 = new BuildTargetIdentifier("test://target2")

    translator.setTargets(List(target1, target2))
    translator.notifyBuildStarted("inv-2")
    translator.notifyBuildFinished("inv-2", 0)

    val finishes = mockClient.allTaskFinishes
    assertEquals(finishes.size, 2)
    finishes.foreach { f =>
      assertEquals(f.getDataKind, TaskFinishDataKind.COMPILE_REPORT)
      assertEquals(f.getStatus, StatusCode.OK)
      val report = gson.fromJson(
        gson.toJson(f.getData),
        classOf[CompileReport],
      )
      assertEquals(report.getErrors: Int, 0)
    }
  }

  test("notifyBuildFinished with targets reports errors on failure") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target = new BuildTargetIdentifier("test://target1")

    translator.setTargets(List(target))
    translator.notifyBuildStarted("inv-err")
    translator.notifyBuildFinished("inv-err", 1)

    val finishes = mockClient.allTaskFinishes
    assertEquals(finishes.size, 1)
    assertEquals(finishes.head.getStatus, StatusCode.ERROR)
    val report = gson.fromJson(
      gson.toJson(finishes.head.getData),
      classOf[CompileReport],
    )
    assertEquals(report.getErrors: Int, 1)
    assertEquals(report.getTarget.getUri, "test://target1")
  }

  test("originId is propagated to TaskStart and TaskFinish") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.setOriginId("my-origin-123")
    translator.notifyBuildStarted("inv-1")
    translator.notifyBuildFinished("inv-1", 0)

    val starts = mockClient.allTaskStarts
    val finishes = mockClient.allTaskFinishes
    assertEquals(starts.head.getOriginId, "my-origin-123")
    assertEquals(finishes.head.getOriginId, "my-origin-123")
  }

  test("onBuildStderr parses [N / M] progress and emits TaskProgress") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target = new BuildTargetIdentifier("test://target1")

    translator.setOriginId("inv-progress")
    translator.setTargets(List(target))
    translator.notifyBuildStarted("inv-progress")

    translator.onBuildStderr("[3 / 42] Compiling scala //src/main:lib")

    val progress = mockClient.allTaskProgress
    assertEquals(progress.size, 1)
    assertEquals(progress.head.getProgress: Long, 3L)
    assertEquals(progress.head.getTotal: Long, 42L)
    assertEquals(progress.head.getDataKind, "bazel-progress")
    assert(progress.head.getMessage.contains("[3 / 42]"))

    val data = progress.head.getData.asInstanceOf[JsonObject]
    val uri = data.getAsJsonObject("target").get("uri").getAsString
    assertEquals(uri, "test://target1")
  }

  test("onBuildStderr handles multiple progress updates") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target = new BuildTargetIdentifier("test://t")

    translator.setOriginId("inv-multi")
    translator.setTargets(List(target))
    translator.notifyBuildStarted("inv-multi")

    translator.onBuildStderr("[1 / 10] Loading...")
    translator.onBuildStderr("[5 / 10] Compiling...")
    translator.onBuildStderr("[10 / 10] Done")

    val progress = mockClient.allTaskProgress
    assertEquals(progress.size, 3)
    assertEquals(progress(0).getProgress: Long, 1L)
    assertEquals(progress(1).getProgress: Long, 5L)
    assertEquals(progress(2).getProgress: Long, 10L)
    progress.foreach(p => assertEquals(p.getTotal: Long, 10L))
  }

  test("onBuildStderr ignores non-progress lines") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target = new BuildTargetIdentifier("test://t")

    translator.setOriginId("inv-ignore")
    translator.setTargets(List(target))
    translator.notifyBuildStarted("inv-ignore")

    translator.onBuildStderr("INFO: Build completed successfully")
    translator.onBuildStderr("Loading: 0 packages loaded")
    translator.onBuildStderr("")

    assertEquals(mockClient.allTaskProgress.size, 0)
  }

  test("onBuildStderr does nothing without targets set") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.setOriginId("inv-no-targets")
    translator.onBuildStderr("[3 / 10] Compiling")

    assertEquals(mockClient.allTaskProgress.size, 0)
  }

  test("clearState resets targets and originId") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val target = new BuildTargetIdentifier("test://t")

    translator.setOriginId("origin-1")
    translator.setTargets(List(target))
    translator.clearState()
    translator.notifyBuildStarted("inv-after-clear")

    val starts = mockClient.allTaskStarts
    assertEquals(starts.size, 1)
    // No targets → fallback path, no COMPILE_TASK data kind
    assertEquals(starts.head.getDataKind, null)
    assertEquals(starts.head.getOriginId, null)
  }

  test("parseDiagnosticsFromStderr parses error diagnostic") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val targetId = new BuildTargetIdentifier("test://target")

    translator.setOriginId("compile-1")
    val lines = List(
      "/workspace/Hello.scala:4:20: error: type mismatch"
    )
    translator.parseDiagnosticsFromStderr(lines, targetId)

    val diags = mockClient.allDiagnostics
    assertEquals(diags.size, 1)
    val params = diags.head
    assertEquals(
      params.getDiagnostics.get(0).getSeverity,
      DiagnosticSeverity.ERROR,
    )
    assertEquals(
      params.getDiagnostics.get(0).getMessage,
      "type mismatch",
    )
    assertEquals(
      params.getDiagnostics.get(0).getRange.getStart.getLine: Int,
      3,
    )
    assertEquals(
      params.getDiagnostics.get(0).getRange.getStart.getCharacter: Int,
      19,
    )
    assertEquals(params.getOriginId, "compile-1")
  }

  test("parseDiagnosticsFromStderr parses warning diagnostic") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val targetId = new BuildTargetIdentifier("test://target")

    val lines = List(
      "/workspace/Foo.scala:10:5: warning: unused import"
    )
    translator.parseDiagnosticsFromStderr(lines, targetId)

    val diags = mockClient.allDiagnostics
    assertEquals(diags.size, 1)
    assertEquals(
      diags.head.getDiagnostics.get(0).getSeverity,
      DiagnosticSeverity.WARNING,
    )
  }

  test("parseDiagnosticsFromStderr ignores non-diagnostic lines") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val targetId = new BuildTargetIdentifier("test://target")

    val lines = List(
      "Loading: 0 packages loaded",
      "INFO: Build completed successfully",
      "some random line",
    )
    translator.parseDiagnosticsFromStderr(lines, targetId)

    assertEquals(mockClient.allDiagnostics.size, 0)
  }

  test("clearOriginId stops propagating originId") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    translator.setOriginId("origin-1")
    translator.notifyBuildStarted("inv-1")
    translator.clearOriginId()
    translator.notifyBuildStarted("inv-2")

    val starts = mockClient.allTaskStarts
    assertEquals(starts.size, 2)
    assertEquals(starts(0).getOriginId, "origin-1")
    assertNotEquals(starts(1).getOriginId, "origin-1")
  }

  test("onRawStreamEvent returns response without crashing on invalid data") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    val response = translator.onRawStreamEvent(Array[Byte](1, 2, 3))
    assert(response != null)
  }

  test("onRawStreamEvent echoes back sequence_number from request") {
    import com.google.protobuf.UnknownFieldSet

    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)

    val streamId = UnknownFieldSet
      .newBuilder()
      .addField(
        1,
        UnknownFieldSet.Field
          .newBuilder()
          .addLengthDelimited(
            com.google.protobuf.ByteString.copyFromUtf8("build-123")
          )
          .build(),
      )
      .build()

    val orderedEvent = UnknownFieldSet
      .newBuilder()
      .addField(
        1,
        UnknownFieldSet.Field
          .newBuilder()
          .addLengthDelimited(streamId.toByteString)
          .build(),
      )
      .addField(
        2,
        UnknownFieldSet.Field.newBuilder().addVarint(5L).build(),
      )
      .build()

    val request = UnknownFieldSet
      .newBuilder()
      .addField(
        4,
        UnknownFieldSet.Field
          .newBuilder()
          .addLengthDelimited(orderedEvent.toByteString)
          .build(),
      )
      .build()

    val response = translator.onRawStreamEvent(request.toByteArray)
    val parsed = UnknownFieldSet.parseFrom(response)

    val seqNum = parsed.getField(2).getVarintList.get(0).longValue()
    assertEquals(seqNum, 5L)
  }
}
