package definition

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.DefinitionResult

import tests.pc.BaseJavaDefinitionSuite

class DeclarationSuite extends BaseJavaDefinitionSuite {

  override def doDefinitionRequest(
      params: CompilerOffsetParams
  ): DefinitionResult =
    presentationCompiler.declaration(params).get()

  check(
    "local-variable",
    """|class A {
       |    public static void main(String args[]){
       |        int x = 42;
       |        int y = @@x;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:4:13: info: definition
       |        int x = 42;
       |            ^
       |""".stripMargin,
  )

  check(
    "field-declaration-not-assignment",
    """|class A {
       |    int x;
       |
       |    A() {
       |        x = 5;
       |    }
       |
       |    int value() {
       |        return @@x;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:9: info: definition
       |    int x;
       |        ^
       |""".stripMargin,
  )

  check(
    "overridden-interface-method",
    """|interface Animal {
       |    void makeSound();
       |}
       |
       |class Dog implements Animal {
       |    public void makeSound() {
       |        System.out.println("Woof");
       |    }
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |        Dog dog = new Dog();
       |        dog.ma@@keSound();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:10: info: definition
       |    void makeSound();
       |         ^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "overridden-multi-level-hierarchy",
    """|interface Animal {
       |    void makeSound();
       |}
       |
       |abstract class Mammal implements Animal {
       |    public void makeSound() {
       |        System.out.println("Generic mammal sound");
       |    }
       |}
       |
       |class Dog extends Mammal {
       |    @Override
       |    public void makeSound() {
       |        System.out.println("Woof");
       |    }
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |        Dog dog = new Dog();
       |        dog.ma@@keSound();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:10: info: definition
       |    void makeSound();
       |         ^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "overridden-multiple-supertypes",
    """|interface Walker {
       |    void move();
       |}
       |
       |interface Swimmer {
       |    void move();
       |}
       |
       |class Duck implements Walker, Swimmer {
       |    @Override
       |    public void move() {
       |        System.out.println("Waddle");
       |    }
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |        Duck duck = new Duck();
       |        duck.mo@@ve();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:10: info: definition
       |    void move();
       |         ^^^^
       |Definition.java:7:10: info: definition
       |    void move();
       |         ^^^^
       |""".stripMargin,
  )
}
