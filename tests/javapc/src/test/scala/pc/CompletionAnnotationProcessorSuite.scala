package pc

import java.net.URI
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams

import coursierapi.Dependency
import munit.Location
import munit.TestOptions
import tests.pc.BaseJavaCompletionSuite

class CompletionAnnotationProcessorSuite extends BaseJavaCompletionSuite {

  private lazy val lombokJar: String =
    coursierapi.Fetch
      .create()
      .addDependencies(Dependency.of("org.projectlombok", "lombok", "1.18.46"))
      .fetch()
      .asScala
      .find(_.getName.startsWith("lombok"))
      .map(_.getAbsolutePath)
      .getOrElse(sys.error("Lombok jar not found"))

  override protected def extraDependencies: Seq[Dependency] =
    Seq(Dependency.of("org.projectlombok", "lombok", "1.18.46"))

  override protected def extraOptions: List[String] =
    List(
      "-processorpath",
      lombokJar,
      "-processor",
      "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
    )

  def checkDiagnostics(
      name: TestOptions,
      code: String,
      expected: String,
      filename: String = "A.java",
  )(implicit loc: Location): Unit =
    test(name) {
      val params = new VirtualFileParams {
        override def uri(): URI = Paths.get(filename).toUri
        override def text(): String = code
        override def token(): CancelToken = EmptyCancelToken
        override def shouldReturnDiagnostics(): Boolean = true
      }
      val diags = presentationCompiler
        .didChange(params)
        .get()
        .asScala
        .map { diag =>
          val start = diag.getRange().getStart()
          val end = diag.getRange().getEnd()
          s"${start.getLine()}:${start.getCharacter()}-${end.getLine()}:${end.getCharacter()} - ${diag.getMessage()}"
        }
        .toList
      assertNoDiff(diags.mkString("\n"), expected)
    }

  checkDiagnostics(
    "lombok-no-errors",
    """
      |import lombok.Data;
      |
      |@Data
      |class Person {
      |    private String name;
      |    private int age;
      |}
      |
      |class Usage {
      |    public void test(Person p) {
      |        String name = p.getName();
      |        p.setAge(26);
      |        int age = p.getAge();
      |    }
      |}
      |""".stripMargin,
    "",
  )

  check(
    "lombok-getter",
    """
      |import lombok.Data;
      |
      |@Data
      |class Person {
      |    private String name;
      |    private int age;
      |}
      |
      |class Usage {
      |    public static void main(String[] args) {
      |        Person p = new Person();
      |        p.getName@@
      |    }
      |}
      |""".stripMargin,
    """|getName()
       |""".stripMargin,
    filterText = Some("getName"),
  )

  check(
    "lombok-builder",
    """
      |import lombok.Builder;
      |import lombok.Value;
      |
      |@Value
      |@Builder
      |class Address {
      |    String street;
      |    String city;
      |    String country;
      |}
      |
      |class AddressUsage {
      |    public void test() {
      |        Address a = Address.builder().city@@
      |    }
      |}
      |""".stripMargin,
    """|city(java.lang.String city)
       |""".stripMargin,
    filterText = Some("city"),
  )

  check(
    "lombok-builder-empty-ident",
    """
      |import lombok.Builder;
      |import lombok.Value;
      |
      |@Value
      |@Builder
      |class Address {
      |    String street;
      |    String city;
      |    String country;
      |}
      |
      |class AddressUsage {
      |    public void test() {
      |        Address a = Address.builder().@@
      |    }
      |}
      |""".stripMargin,
    """|city(java.lang.String city)
       |""".stripMargin,
    filterText = Some("city"),
  )

  check(
    "lombok-setter",
    """
      |import lombok.Data;
      |
      |@Data
      |class Person {
      |    private String name;
      |    private int age;
      |}
      |
      |class Usage {
      |    public static void main(String[] args) {
      |        Person p = new Person();
      |        p.setName@@
      |    }
      |}
      |""".stripMargin,
    """|setName(java.lang.String name)
       |""".stripMargin,
    filterText = Some("setName"),
  )

}
