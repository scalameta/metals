package scala.meta.internal.telemetry;

import java.util.List;

public class MetalsLspContext implements ReporterContext {
	final private String metalsVersion;
	final private MetalsUserConfiguration userConfig;
	final private MetalsServerConfiguration serverConfig;
	final private MetalsClientInfo clientInfo;
	final private List<BuildServerConnection> buildServerConnections;

	public MetalsLspContext(String metalsVersion, MetalsUserConfiguration userConfig,
			MetalsServerConfiguration serverConfig, MetalsClientInfo clientInfo,
			List<BuildServerConnection> buildServerConnections) {
		this.metalsVersion = metalsVersion;
		this.userConfig = userConfig;
		this.serverConfig = serverConfig;
		this.clientInfo = clientInfo;
		this.buildServerConnections = buildServerConnections;
	}

	public String getMetalsVersion() {
		return metalsVersion;
	}

	public MetalsUserConfiguration getUserConfig() {
		return userConfig;
	}

	public MetalsServerConfiguration getServerConfig() {
		return serverConfig;
	}

	public MetalsClientInfo getClientInfo() {
		return clientInfo;
	}

	public List<BuildServerConnection> getBuildServerConnections() {
		return buildServerConnections;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((metalsVersion == null) ? 0 : metalsVersion.hashCode());
		result = prime * result + ((userConfig == null) ? 0 : userConfig.hashCode());
		result = prime * result + ((serverConfig == null) ? 0 : serverConfig.hashCode());
		result = prime * result + ((clientInfo == null) ? 0 : clientInfo.hashCode());
		result = prime * result + ((buildServerConnections == null) ? 0 : buildServerConnections.hashCode());
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
		MetalsLspContext other = (MetalsLspContext) obj;
		if (metalsVersion == null) {
			if (other.metalsVersion != null)
				return false;
		} else if (!metalsVersion.equals(other.metalsVersion))
			return false;
		if (userConfig == null) {
			if (other.userConfig != null)
				return false;
		} else if (!userConfig.equals(other.userConfig))
			return false;
		if (serverConfig == null) {
			if (other.serverConfig != null)
				return false;
		} else if (!serverConfig.equals(other.serverConfig))
			return false;
		if (clientInfo == null) {
			if (other.clientInfo != null)
				return false;
		} else if (!clientInfo.equals(other.clientInfo))
			return false;
		if (buildServerConnections == null) {
			if (other.buildServerConnections != null)
				return false;
		} else if (!buildServerConnections.equals(other.buildServerConnections))
			return false;
		return true;
	}

}
