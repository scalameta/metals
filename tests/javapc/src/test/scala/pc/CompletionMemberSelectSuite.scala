package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionMemberSelectSuite extends BaseJavaCompletionSuite {

  for ((name, prefix) <- List("empty" -> "", "prefix" -> "D")) {
    check(
      s"static-field-before-existing-select-$name",
      s"""|class A {
          |  void run() {
          |    Box.$prefix@@.getValue();
          |  }
          |}
          |class Box {
          |  public static final Box DEFAULT = new Box();
          |  public int getValue() { return 0; }
          |}
          |""".stripMargin,
      "DEFAULT\n",
      editedFile = """|class A {
                      |  void run() {
                      |    Box.DEFAULT.getValue();
                      |  }
                      |}
                      |class Box {
                      |  public static final Box DEFAULT = new Box();
                      |  public int getValue() { return 0; }
                      |}
                      |""".stripMargin,
    )
  }

  check(
    "instance-field-before-existing-select",
    """|class A {
       |  void run(Config config) {
       |    config.@@.trim();
       |  }
       |}
       |class Config {
       |  public String value;
       |}
       |""".stripMargin,
    "value\n",
    editedFile = """|class A {
                    |  void run(Config config) {
                    |    config.value.trim();
                    |  }
                    |}
                    |class Config {
                    |  public String value;
                    |}
                    |""".stripMargin,
    filterItem = _.getLabel == "value",
  )

  check(
    "member-sort-text",
    """|class A {
       |  void run(Config config) {
       |    config.@@
       |  }
       |}
       |class Config {
       |  public String roundType;
       |  public int roundUnit;
       |}
       |""".stripMargin,
    """|roundType
       |roundUnit
       |equals(java.lang.Object obj)
       |""".stripMargin,
    filterItem = item =>
      Set("roundType", "roundUnit").contains(item.getLabel) ||
        item.getLabel.startsWith("equals("),
  )

  check(
    "static field member",
    """
      |class Simple{
      |    public static int NUMBER = 42;
      |
      |    public static void main(String args[]){
      |        new Simple().NU@@
      |    }
      |}
      |""".stripMargin,
    """
      |NUMBER
      |""".stripMargin,
  )

  check(
    "array member",
    """
      |class Simple{
      |    public static void main(String args[]){
      |        new int[42].le@@
      |    }
      |}
      |""".stripMargin,
    """
      |length
      |""".stripMargin,
  )

  check(
    "type variable",
    """
      |class Simple{
      |    public static int NUMBER = 42;
      |
      |    public static void test<T extends Simple>() {
      |        T.NUM@@
      |    }
      |}
      |""".stripMargin,
    """
      |NUMBER
      |""".stripMargin,
  )

  check(
    "after-statement",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    name.sub@@
      |    System.out.println("Perfect " + name);
      |  }
      |}
      |""".stripMargin,
    """|substring(int beginIndex)
       |substring(int beginIndex, int endIndex)
       |subSequence(int beginIndex, int endIndex)
       |""".stripMargin,
  )

  check(
    "same-line",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    name.sub@@  System.out.println("Perfect " + name);
      |  }
      |}
      |""".stripMargin,
    """|substring(int beginIndex)
       |substring(int beginIndex, int endIndex)
       |subSequence(int beginIndex, int endIndex)
       |""".stripMargin,
  )

  check(
    "inside-parens-no-space",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    System.out.println(name.sub@@);
      |  }
      |}
      |""".stripMargin,
    """|substring(int beginIndex)
       |substring(int beginIndex, int endIndex)
       |subSequence(int beginIndex, int endIndex)
       |""".stripMargin,
  )

  check(
    "inside-word",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    System.out.println(name.sub@@S );
      |  }
      |}
      |""".stripMargin,
    """|substring(int beginIndex)
       |substring(int beginIndex, int endIndex)
       |subSequence(int beginIndex, int endIndex)
       |""".stripMargin,
  )

  check(
    "empty-select",
    """
      |class Perfect {
      |  
      |  void println() {
      |    Perfect perfect = new Perfect();
      |    perfect.@@
      |  }
      |}
      |""".stripMargin,
    """|println()
       |getClass()
       |hashCode()
       |equals(java.lang.Object obj)
       |clone()
       |toString()
       |notify()
       |notifyAll()
       |wait()
       |wait(long arg0)
       |wait(long timeoutMillis, int nanos)
       |finalize()
       |""".stripMargin,
  )

  check(
    "completable-future-select",
    """
      |
      |import java.util.concurrent.CompletableFuture;
      |
      |class Perfect {
      |  
      |  void println() {
      |    CompletableFuture.@@
      |  }
      |}
      |""".stripMargin,
    "",
    // let's make sure we don't get any <clinit> method
    filterText = Some("cl"),
  )

  check(
    "completable-future-static-select",
    """
      |
      |import java.util.concurrent.CompletableFuture;
      |
      |class Perfect {
      |  
      |  void println() {
      |    CompletableFuture.allO@@
      |  }
      |}
      |""".stripMargin,
    """|allOf(java.util.concurrent.CompletableFuture<?>[] cfs)
       |""".stripMargin,
  )

  check(
    "completable-future-instance-select",
    """
      |
      |import java.util.concurrent.CompletableFuture;
      |
      |class Perfect {
      |
      |  void println() {
      |    CompletableFuture<?> cf = new CompletableFuture<>();
      |    cf.completeExc@@
      |  }
      |}
      |""".stripMargin,
    """|completeExceptionally(java.lang.Throwable ex)
       |""".stripMargin,
  )

  check(
    "super-select",
    """|class Parent {
       |  public void parentMethod() {}
       |}
       |
       |class Child extends Parent {
       |  @Override
       |  public void parentMethod() {
       |    super.parentM@@
       |  }
       |}
       |""".stripMargin,
    """|parentMethod()
       |""".stripMargin,
  )

  check(
    "super-select-empty",
    """|class Parent {
       |  public void parentMethod() {}
       |}
       |
       |class Child extends Parent {
       |  @Override
       |  public void parentMethod() {
       |    super.@@
       |  }
       |}
       |""".stripMargin,
    """|parentMethod()
       |""".stripMargin,
    filterItem = _.getLabel().startsWith("parent"),
  )

  check(
    "only-static-members-select",
    """
      |class TestStaticMethods {
      |  public static String hello(){
      |        return "hello";
      |    }
      |
      |    public String nonStaticHello(){
      |        return "nonStatic";
      |    }
      |
      |    public static void main(String[] args) {
      |        TestStaticMethods.@@
      |    }
      |}
      |""".stripMargin,
    """|hello()
       |main(java.lang.String[] args)
       |""".stripMargin,
  )
}
