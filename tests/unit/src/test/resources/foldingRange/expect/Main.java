package example;

   />>comment>>**
 Very
 long
 comment
 to
 wrap
 */<<comment<<
public class Test {>>region>>

	abstract class A {
		abstract void hello();
	}

	abstract class B extends A {>>region>>
		@Override
		void hello() {
			System.out.println("Hello!");
		}
	}<<region<<

	class C extends B {>>region>>
		@Override
		void hello() {
			System.out.println("Bye!");
		}
		
		String hello(String str) {>>region>>
			System.out.println("Bye!");
			return "asssd";
		}<<region<<
	}<<region<<
}<<region<<