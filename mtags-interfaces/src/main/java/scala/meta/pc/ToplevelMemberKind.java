package scala.meta.pc;

import java.util.Arrays;
import java.util.List;

public enum ToplevelMemberKind {
    TYPE("Type"),
    IMPLICIT_CLASS("ImplicitClass");

    private final String value;

    ToplevelMemberKind(String value) {
        this.value = value;
    }
    
    public static final List<ToplevelMemberKind> ALL = Arrays.asList(TYPE, IMPLICIT_CLASS);
}

