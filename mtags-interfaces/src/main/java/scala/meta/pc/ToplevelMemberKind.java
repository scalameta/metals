package scala.meta.pc;

public enum ToplevelMemberKind {
    TYPE("Type"),
    IMPLICIT_CLASS("ImplicitClass");

    private final String value;

    ToplevelMemberKind(String value) {
        this.value = value;
    }
}

