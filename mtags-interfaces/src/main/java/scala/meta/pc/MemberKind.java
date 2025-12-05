package scala.meta.pc;

import java.util.Arrays;
import java.util.List;

public enum MemberKind {
    TOPLEVEL_TYPE("ToplevelType"),
    TOPLEVEL_IMPLICIT_CLASS("ToplevelImplicitClass");

    private final String value;

    MemberKind(String value) {
        this.value = value;
    }
    
    public static final List<MemberKind> ALL = Arrays.asList(TOPLEVEL_TYPE, TOPLEVEL_IMPLICIT_CLASS);
}

