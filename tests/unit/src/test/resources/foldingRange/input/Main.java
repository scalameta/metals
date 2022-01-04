package example;

   /**
 Very
 long
 comment
 to
 wrap
 */
public class Test {

	abstract class A {
		abstract void hello();
	}

	abstract class B extends A {
		@Override
		void hello() {
			System.out.println("Hello!");
		}
	}

	class C extends B {
		@Override
		void hello() {
			System.out.println("Bye!");
		}
		
		String hello(String str) {
			System.out.println("Bye!");
			return "asssd";
		}
	}
}