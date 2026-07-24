package tests.bazel

import java.io.ByteArrayOutputStream

import scala.meta.internal.metals.mbt.importer.BazelStreamedProto
import scala.meta.internal.metals.mbt.importer.BazelTargetsProtoDump

import com.google.protobuf.CodedOutputStream
import tests.BaseSuite

// [[BazelTargetsProtoDump]] over synthetic wire bytes built with Bazel's
// `build.proto` field numbers, including fields the decoder must skip.
class BazelStreamedProtoSuite extends BaseSuite {

  private val v2_12 = "@rules_scala_config//:scala_version_2_12_21"
  private val v3_3 = "@rules_scala_config//:scala_version_3_3_0"

  private type Target = Array[Byte]
  private type Attr = Array[Byte]

  // Attribute.Discriminator values (build.proto); skipped by the decoder, so
  // only their presence as a varint field matters, not the exact value.
  private val STRING = 2
  private val LABEL = 3
  private val LABEL_LIST = 6
  private val SELECTOR_LIST = 20

  private def encode(write: CodedOutputStream => Unit): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val cos = CodedOutputStream.newInstance(out)
    write(cos)
    cos.flush()
    out.toByteArray
  }

  /** `Target { type = 1; rule = 2; }` wrapping a `Rule`. */
  private def ruleTarget(
      name: String,
      ruleClass: String = "scala_library",
      attributes: Seq[Attr] = Nil,
      ruleInput: Seq[String] = Nil,
      ruleOutput: Seq[String] = Nil,
  ): Target = {
    val rule = encode { cos =>
      cos.writeString(1, name) // Rule.name
      cos.writeString(2, ruleClass) // Rule.rule_class
      cos.writeString(3, "/ws/pkg/BUILD:1:1") // Rule.location — must be skipped
      attributes.foreach(cos.writeByteArray(4, _)) // Rule.attribute
      ruleInput.foreach(cos.writeString(5, _)) // Rule.rule_input
      ruleOutput.foreach(cos.writeString(6, _)) // Rule.rule_output
    }
    encode { cos =>
      cos.writeInt32(1, 1) // Target.type = RULE — must be skipped
      cos.writeByteArray(2, rule) // Target.rule
    }
  }

  /** A non-rule `Target` (`SOURCE_FILE`); the decoder yields no rule for it. */
  private def nonRuleTarget: Target = encode { cos =>
    cos.writeInt32(1, 5) // Target.type = SOURCE_FILE
    cos.writeByteArray(3, encode(_.writeString(1, "//pkg:Main.scala")))
  }

  private def attribute(
      name: String,
      discriminator: Int,
  )(write: CodedOutputStream => Unit): Attr =
    encode { cos =>
      cos.writeString(1, name) // Attribute.name
      cos.writeInt32(2, discriminator) // Attribute.type — must be skipped
      write(cos)
    }

  private def selectorList(
      discriminator: Int
  )(writeEntries: CodedOutputStream => Unit): Array[Byte] = {
    val selector = encode { cos =>
      cos.writeInt32(2, 0) // Selector.has_default_value — must be skipped
      writeEntries(cos)
    }
    encode { cos =>
      cos.writeInt32(1, discriminator) // SelectorList.type — must be skipped
      cos.writeByteArray(2, selector) // SelectorList.elements (one selector)
    }
  }

  /** A `select()` over a scalar string: each branch carries field 3. */
  private def selectStringAttr(
      name: String
  )(branches: (String, String)*): Attr = {
    val list = selectorList(STRING) { cos =>
      branches.foreach { case (label, value) =>
        val entry = encode { e =>
          e.writeString(1, label) // SelectorEntry.label
          e.writeString(3, value) // SelectorEntry.string_value
        }
        cos.writeByteArray(1, entry) // Selector.entries
      }
    }
    attribute(name, SELECTOR_LIST)(_.writeByteArray(21, list))
  }

  /** A plain scalar attribute (`Attribute.string_value`, field 5). */
  private def stringAttr(name: String, value: String): Attr =
    attribute(name, LABEL)(_.writeString(5, value))

  /** A plain list attribute (`Attribute.string_list_value`, field 6). */
  private def stringListAttr(name: String)(values: String*): Attr =
    attribute(name, LABEL_LIST)(cos => values.foreach(cos.writeString(6, _)))

  private def dump(targets: Target*): BazelTargetsProtoDump = {
    // The stream is the length-delimited concatenation of every Target.
    val stream = encode(cos => targets.foreach(cos.writeByteArrayNoTag))
    new BazelTargetsProtoDump(BazelStreamedProto.parseRules(stream))
  }

  test("string-value-select-branches-are-unioned") {
    // A STRING attribute that is itself a select(): each branch carries a
    // scalar value (in the binary form field 3, distinct from
    // Attribute.string_value's field 5).
    val d = dump(
      ruleTarget(
        "//pkg:c",
        attributes = Seq(
          selectStringAttr("scala_version")(
            v2_12 -> "2.12.21",
            v3_3 -> "3.3.0",
          )
        ),
      )
    )
    assertEquals(
      d.getStrings("scala_version")("//pkg:c"),
      List("2.12.21", "3.3.0"),
    )
  }

  test("import-target-jars-and-srcjar-are-extracted") {
    val d = dump(
      ruleTarget(
        "//third_party:mylib",
        ruleClass = "scala_import",
        attributes = Seq(
          stringListAttr("jars")("//third_party:mylib.jar"),
          stringAttr("srcjar", "//third_party:mylib-sources.jar"),
        ),
      ),
      ruleTarget(
        "//third_party:myother",
        ruleClass = "java_import",
        attributes = Seq(stringListAttr("jars")("//third_party:myother.jar")),
      ),
      // A non-import rule never appears in the import views, even with `jars`.
      ruleTarget(
        "//pkg:lib",
        attributes = Seq(stringListAttr("jars")("//pkg:lib.jar")),
      ),
    )
    assertEquals(
      d.jarLabelsByImportTarget,
      Map(
        "//third_party:mylib" -> List("//third_party:mylib.jar"),
        "//third_party:myother" -> List("//third_party:myother.jar"),
      ),
    )
    // `srcjar` exists only on scala_import; java_import rules have no entry.
    assertEquals(
      d.sourcesJarByImportTarget,
      Map("//third_party:mylib" -> Some("//third_party:mylib-sources.jar")),
    )
  }

  test("is-empty-distinguishes-no-rules-from-parsed-rules") {
    // An empty / unsupported query produces no rules — the importer uses this
    // to surface the failure instead of writing an empty build silently.
    assertEquals(dump().isEmpty, true)
    // Non-rule targets (e.g. source files) still leave the dump empty.
    assertEquals(dump(nonRuleTarget).isEmpty, true)
    assertEquals(dump(ruleTarget("//pkg:lib")).isEmpty, false)
  }
}
