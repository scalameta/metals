package scala.meta.pc;

public interface JavacServicesOverridesConfig {
  boolean names();

  boolean attr();

  boolean typeEnter();

  boolean enter();

  public static JavacServicesOverridesConfig EMPTY =
      new JavacServicesOverridesConfig() {
        @Override
        public boolean names() {
          return false;
        }

        @Override
        public boolean attr() {
          return false;
        }

        @Override
        public boolean typeEnter() {
          return false;
        }

        @Override
        public boolean enter() {
          return false;
        }
      };
}
