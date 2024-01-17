package tests.telemetry

import scala.meta.internal.telemetry

import tests.BaseSuite

/* Test checking correctness of to/from JSON serialization for telemetry model.
   It's purpouse is to check if Optional[T] fields and Java collections are correctly serialized.
   Optional fields would be used to evolve the model in backward compatible way, however by default GSON can initialize Optional fields to null if they're missing in the json fields.
 */
class SerializationSuite extends BaseSuite {

  test("serialization") {
    def withConfig(emptyCollections: Boolean) = {
      Seq(
        SampleReports.metalsLspReport(
          emptyOptionals = emptyCollections,
          emptyLists = emptyCollections,
          emptyMaps = emptyCollections,
        ),
        SampleReports.scalaPresentationCompilerReport(
          emptyOptionals = emptyCollections,
          emptyLists = emptyCollections,
          emptyMaps = emptyCollections,
        ),
      ).foreach { report =>
        val codec = telemetry.GsonCodecs.gson
        assert(!codec.serializeNulls(), "Codec should not serialize nulls")
        val json = codec.toJson(report)
        val fromJson = codec.fromJson(json, report.getClass())
        assertEquals(report, fromJson)
      }
    }

    withConfig(emptyCollections = false)
    withConfig(emptyCollections = true)
  }
}
