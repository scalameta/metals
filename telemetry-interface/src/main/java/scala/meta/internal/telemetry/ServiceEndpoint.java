package scala.meta.internal.telemetry;

public class ServiceEndpoint<I, O> {
	final private String uri;
	final private String method;
	final private Class<I> inputType;
	final private Class<O> outputType;

	public ServiceEndpoint(String method, String uri, Class<I> inputType, Class<O> outputType) {
		this.uri = uri;
		this.method = method;
		this.inputType = inputType;
		this.outputType = outputType;
	}

	public String getUri() {
		return uri;
	}

	public String getMethod() {
		return method;
	}

	public Class<I> getInputType() {
		return inputType;
	}

	public Class<O> getOutputType() {
		return outputType;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((uri == null) ? 0 : uri.hashCode());
		result = prime * result + ((method == null) ? 0 : method.hashCode());
		result = prime * result + ((inputType == null) ? 0 : inputType.hashCode());
		result = prime * result + ((outputType == null) ? 0 : outputType.hashCode());
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
		ServiceEndpoint other = (ServiceEndpoint) obj;
		if (uri == null) {
			if (other.uri != null)
				return false;
		} else if (!uri.equals(other.uri))
			return false;
		if (method == null) {
			if (other.method != null)
				return false;
		} else if (!method.equals(other.method))
			return false;
		if (inputType == null) {
			if (other.inputType != null)
				return false;
		} else if (!inputType.equals(other.inputType))
			return false;
		if (outputType == null) {
			if (other.outputType != null)
				return false;
		} else if (!outputType.equals(other.outputType))
			return false;
		return true;
	}

}