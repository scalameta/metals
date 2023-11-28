package scala.meta.internal.telemetry;

import java.util.Optional;

public class ReporterContextUnion {
	final private Optional<MetalsLspContext> metalsLSP;
	final private Optional<ScalaPresentationCompilerContext> scalaPresentationCompiler;
	final private Optional<UnknownProducerContext> unknown;

	ReporterContextUnion(Optional<MetalsLspContext> metalsLSP,
			Optional<ScalaPresentationCompilerContext> scalaPresentationCompiler,
			Optional<UnknownProducerContext> unknown) {
		this.metalsLSP = metalsLSP;
		this.scalaPresentationCompiler = scalaPresentationCompiler;
		this.unknown = unknown;
	}

	public ReporterContext get() {
		if (metalsLSP.isPresent())
			return metalsLSP.get();
		if (scalaPresentationCompiler.isPresent())
			return scalaPresentationCompiler.get();
		if (unknown.isPresent())
			return unknown.get();
		throw new IllegalStateException("None of union values is defined");
	}

	public static ReporterContextUnion metalsLSP(MetalsLspContext ctx) {
		return new ReporterContextUnion(Optional.of(ctx), Optional.empty(), Optional.empty());
	}

	public static ReporterContextUnion scalaPresentationCompiler(ScalaPresentationCompilerContext ctx) {
		return new ReporterContextUnion(Optional.empty(), Optional.of(ctx), Optional.empty());
	}

	public static ReporterContextUnion unknown(UnknownProducerContext ctx) {
		return new ReporterContextUnion(Optional.empty(), Optional.empty(), Optional.of(ctx));
	}

	public Optional<MetalsLspContext> getMetalsLSP() {
		return metalsLSP;
	}

	public Optional<ScalaPresentationCompilerContext> getScalaPresentationCompiler() {
		return scalaPresentationCompiler;
	}

	public Optional<UnknownProducerContext> getUnknown() {
		return unknown;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((metalsLSP == null) ? 0 : metalsLSP.hashCode());
		result = prime * result + ((scalaPresentationCompiler == null) ? 0 : scalaPresentationCompiler.hashCode());
		result = prime * result + ((unknown == null) ? 0 : unknown.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ReporterContextUnion other = (ReporterContextUnion) obj;
		if (metalsLSP == null) {
			if (other.metalsLSP != null)
				return false;
		} else if (!metalsLSP.equals(other.metalsLSP))
			return false;
		if (scalaPresentationCompiler == null) {
			if (other.scalaPresentationCompiler != null)
				return false;
		} else if (!scalaPresentationCompiler.equals(other.scalaPresentationCompiler))
			return false;
		if (unknown == null) {
			if (other.unknown != null)
				return false;
		} else if (!unknown.equals(other.unknown))
			return false;
		return true;
	}
}