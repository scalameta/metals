package scala.meta.metals.maven;

import java.io.File;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;

/**
 * Exports the Maven build structure to a .metals/mbt-maven.json file.
 *
 * <p>The output path is controlled by -DmbtOutputFile=<path>.
 */
@Mojo(
    name = "export",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyCollection = ResolutionScope.TEST,
    requiresDependencyResolution = ResolutionScope.NONE,
    aggregator = true,
    threadSafe = true)
public class MbtMojo extends AbstractMojo {

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
  private List<MavenProject> reactorProjects;

  /** Path where the plugin writes its JSON output. Set by Metals via -DmbtOutputFile=. */
  @Parameter(property = "mbtOutputFile", required = true)
  private File outputFile;

  /**
   * Whether to resolve and attach sources JARs for external dependencies. Enabled by default;
   * disable with -DdownloadSources=false.
   */
  @Parameter(property = "downloadSources", defaultValue = "true")
  private boolean downloadSources;

  @Component private RepositorySystem repoSystem;

  @Component private ToolchainManager toolchainManager;

  @Component private ProjectBuilder projectBuilder;

  public void execute() throws MojoExecutionException, MojoFailureException {
    MbtMojoImpl.run(this);
  }

  public MavenSession getSession() {
    return session;
  }

  public List<MavenProject> getReactorProjects() {
    return reactorProjects;
  }

  public File getOutputFile() {
    return outputFile;
  }

  public boolean isDownloadSources() {
    return downloadSources;
  }

  public RepositorySystem getRepoSystem() {
    return repoSystem;
  }

  public ToolchainManager getToolchainManager() {
    return toolchainManager;
  }

  public ProjectBuilder getProjectBuilder() {
    return projectBuilder;
  }

  public RepositorySystemSession getRepositorySession() {
    return session.getRepositorySession();
  }

  public File getLocalRepositoryBasedir() {
    return new File(session.getLocalRepository().getBasedir());
  }
}
