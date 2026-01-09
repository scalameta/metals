#!/usr/bin/env bun
/**
 * extract-mbt.ts - Generate .metals/mbt.json for Maven/Gradle projects
 *
 * This script extracts dependency information from Maven or Gradle builds
 * and writes it to .metals/mbt.json for use with Metals 2.0+.
 *
 * INSTALLATION:
 *   curl -fsSL https://bun.sh/install | bash
 *   bun add -g listr2
 *
 * USAGE:
 *   # Download and run directly:
 *   curl -fsSL https://raw.githubusercontent.com/scalameta/metals/main/bin/extract-mbt.ts | bun -
 *
 *   # Or save locally and run:
 *   curl -fsSL https://raw.githubusercontent.com/scalameta/metals/main/bin/extract-mbt.ts -o extract-mbt.ts
 *   bun extract-mbt.ts
 *
 * OPTIONS:
 *   --maven     Force Maven extraction (even if Gradle files exist)
 *   --gradle    Force Gradle extraction (even if Maven files exist)
 *   --sources   Download source jars before extraction
 *
 * NOTE: This script is intentionally simple and may not handle all edge cases.
 * Feel free to download, modify, and extend it for your specific needs.
 * Use AI assistants to help debug or add features for your build setup.
 */
import { $ } from "bun";
import { existsSync, mkdirSync, writeFileSync } from "fs";
import { join } from "path";
import { homedir } from "os";
import { Listr } from "listr2";

interface DependencyModule {
  id: string; // "org:name:version"
  jar: string; // absolute path to jar
  sources?: string; // absolute path to sources jar
}

interface MbtBuild {
  dependencyModules: DependencyModule[];
}

const cwd = process.cwd();
const args = process.argv.slice(2);
const downloadSources = args.includes("--sources");
const forceMaven = args.includes("--maven");
const forceGradle = args.includes("--gradle");
const m2Repo = process.env.M2_REPO || join(homedir(), ".m2", "repository");

if (args.includes("--help") || args.includes("-h")) {
  console.log(`Usage: bun extract-mbt.ts [options]

Generates .metals/mbt.json with dependency information for Metals 2.0+

Options:
  --maven     Force Maven (even if Gradle files exist)
  --gradle    Force Gradle (even if Maven files exist)
  --sources   Download source jars before extraction
  --help, -h  Show this help message

See script header for installation and more details.
`);
  process.exit(0);
}

function jarPathFromCoords(
  org: string,
  name: string,
  version: string,
  classifier?: string
): string {
  const orgPath = org.replace(/\./g, "/");
  const jarName = classifier
    ? `${name}-${version}-${classifier}.jar`
    : `${name}-${version}.jar`;
  return join(m2Repo, orgPath, name, version, jarName);
}

interface TaskContext {
  modules: DependencyModule[];
  buildOutput?: string;
}

type LineFilter = (line: string) => string | null;

function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  if (minutes > 0) {
    return `${minutes}m ${remainingSeconds}s`;
  }
  return `${seconds}s`;
}

function withTimer(
  baseTitle: string,
  task: any,
  fn: () => Promise<void>
): Promise<void> {
  const startTime = Date.now();
  const updateTitle = () => {
    const elapsed = formatDuration(Date.now() - startTime);
    task.title = `${baseTitle} (${elapsed})`;
  };

  updateTitle();
  const interval = setInterval(updateTitle, 1000);

  return fn().finally(() => {
    clearInterval(interval);
    const elapsed = formatDuration(Date.now() - startTime);
    task.title = `${baseTitle} (${elapsed})`;
  });
}

async function runWithProgress(
  cmd: string[],
  task: any,
  lineFilter: LineFilter
): Promise<{ exitCode: number; output: string }> {
  const proc = Bun.spawn(cmd, {
    stdout: "pipe",
    stderr: "pipe",
  });

  const chunks: string[] = [];
  const decoder = new TextDecoder();
  const recentLines: string[] = [];
  const reader = proc.stdout.getReader();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    const text = decoder.decode(value);
    chunks.push(text);
    buffer += text;

    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const line of lines) {
      const filtered = lineFilter(line);
      if (filtered) {
        recentLines.push(filtered);
        if (recentLines.length > 3) {
          recentLines.shift();
        }
        task.output = recentLines.join("\n");
      }
    }
  }

  const stderrReader = proc.stderr.getReader();
  while (true) {
    const { done, value } = await stderrReader.read();
    if (done) break;
    chunks.push(decoder.decode(value));
  }

  const exitCode = await proc.exited;
  return { exitCode, output: chunks.join("") };
}

const mavenLineFilter: LineFilter = (line) => {
  const trimmed = line.trim();
  if (
    trimmed.startsWith("[INFO] Building ") ||
    (trimmed.startsWith("[INFO] ---") && trimmed.includes("@"))
  ) {
    return trimmed.substring(7);
  }
  return null;
};

const gradleLineFilter: LineFilter = (line) => {
  const trimmed = line.trim();
  if (
    trimmed.startsWith("> Task ") ||
    trimmed.startsWith("> Configure project") ||
    trimmed.startsWith("BUILD ") ||
    trimmed.includes("CONFIGURING") ||
    trimmed.includes("EXECUTING")
  ) {
    return trimmed;
  }
  if (trimmed.startsWith(":") && !trimmed.includes("MBT_DEP")) {
    return trimmed;
  }
  return null;
};

async function extractMaven(ctx: TaskContext, task: any): Promise<void> {
  if (downloadSources) {
    task.output = "Downloading sources...";
    await $`mvn dependency:sources -fn -q`.nothrow().quiet();
  }

  const { exitCode, output } = await runWithProgress(
    ["mvn", "dependency:tree", "-DoutputType=text"],
    task,
    mavenLineFilter
  );
  ctx.buildOutput = output;

  if (exitCode !== 0) {
    if (output.includes("UnsupportedClassVersionError")) {
      throw new Error(
        "Java version mismatch. The project requires a newer Java version."
      );
    }
    throw new Error(`Maven failed with exit code ${exitCode}`);
  }

  task.output = "Parsing dependencies...";
  const seen = new Set<string>();
  const modules: DependencyModule[] = [];

  const depLineRegex = /\[INFO\]\s+[\s|+\\`-]+\s*([\w.-]+:[\w.-]+:jar:[^\s]+)/;

  for (const line of output.split("\n")) {
    const lineMatch = line.match(depLineRegex);
    if (!lineMatch) continue;

    const depPart = lineMatch[1].trim();
    const parts = depPart.split(":");

    if (!parts.includes("jar")) continue;

    let org: string,
      name: string,
      version: string,
      classifier: string | undefined;

    if (parts.length === 5) {
      [org, name, , version] = parts;
    } else if (parts.length === 6) {
      [org, name, , classifier, version] = parts;
    } else {
      continue;
    }

    if (version.includes("SNAPSHOT")) continue;

    const id = `${org}:${name}:${version}`;
    if (seen.has(id)) continue;
    seen.add(id);

    const jarPath = jarPathFromCoords(org, name, version, classifier);
    if (!existsSync(jarPath)) continue;

    const sourcesPath = jarPathFromCoords(org, name, version, "sources");
    modules.push({
      id,
      jar: jarPath,
      ...(existsSync(sourcesPath) && { sources: sourcesPath }),
    });
  }

  ctx.modules = modules;
}

async function extractGradle(ctx: TaskContext, task: any): Promise<void> {
  task.output = "Creating init script...";
  const initScript = `
gradle.projectsEvaluated {
  rootProject.allprojects { p ->
    p.tasks.register('mbtDumpDeps') {
      notCompatibleWithConfigurationCache("Accesses project configurations")
      doLast {
        p.configurations.findAll { 
          it.canBeResolved && !it.name.contains('swiftExport')
        }.each { config ->
          try {
            config.resolvedConfiguration.resolvedArtifacts.each { art ->
              def id = art.moduleVersion.id
              def coord = "\${id.group}:\${id.name}:\${id.version}"
              def jarFile = art.file
              def jar = jarFile.absolutePath
              def sources = ''
              
              // Look for sources in sibling hash directories
              // Gradle cache: .../files-2.1/group/name/version/HASH/file.jar
              def versionDir = jarFile.parentFile?.parentFile
              if (versionDir?.exists()) {
                versionDir.eachDir { hashDir ->
                  hashDir.eachFile { f ->
                    if (f.name.endsWith('-sources.jar')) {
                      sources = f.absolutePath
                    }
                  }
                }
              }
              println "MBT_DEP|\${coord}|\${jar}|\${sources}"
            }
          } catch (Exception e) { /* skip unresolvable */ }
        }
      }
    }
  }
}
`;
  const initPath = join(cwd, ".metals", "mbt-init.gradle");
  mkdirSync(join(cwd, ".metals"), { recursive: true });
  writeFileSync(initPath, initScript);

  const gradleCmd = existsSync(join(cwd, "gradlew")) ? "./gradlew" : "gradle";
  const { exitCode, output } = await runWithProgress(
    [
      gradleCmd,
      "--init-script",
      initPath,
      "mbtDumpDeps",
      "--continue",
      "--no-configuration-cache",
    ],
    task,
    gradleLineFilter
  );
  ctx.buildOutput = output;

  task.output = "Parsing dependencies...";
  const seen = new Set<string>();
  const modules: DependencyModule[] = [];

  for (const line of output.split("\n")) {
    if (!line.startsWith("MBT_DEP|")) continue;
    const [, id, jar, sources] = line.split("|");
    if (!id || !jar || !jar.endsWith(".jar") || seen.has(id)) continue;
    seen.add(id);
    modules.push({
      id,
      jar,
      ...(sources && { sources }),
    });
  }

  ctx.modules = modules;
  if (exitCode !== 0) {
    if (modules.length > 0) {
      task.output = `Warning: Gradle had errors but extracted ${modules.length} dependencies`;
    } else {
      throw new Error(`Gradle failed with exit code ${exitCode}`);
    }
  }
}

async function main() {
  const hasMaven = existsSync(join(cwd, "pom.xml"));
  const hasGradle =
    existsSync(join(cwd, "build.gradle")) ||
    existsSync(join(cwd, "build.gradle.kts"));

  let useMaven: boolean;
  if (forceMaven && forceGradle) {
    console.error("Cannot specify both --maven and --gradle");
    process.exit(1);
  } else if (forceMaven) {
    if (!hasMaven) {
      console.error("--maven specified but no pom.xml found");
      process.exit(1);
    }
    useMaven = true;
  } else if (forceGradle) {
    if (!hasGradle) {
      console.error("--gradle specified but no build.gradle found");
      process.exit(1);
    }
    useMaven = false;
  } else if (hasMaven && hasGradle) {
    console.error(
      "Both Maven and Gradle detected. Use --maven or --gradle to choose."
    );
    process.exit(1);
  } else if (hasMaven) {
    useMaven = true;
  } else if (hasGradle) {
    useMaven = false;
  } else {
    console.error("No pom.xml or build.gradle found");
    process.exit(1);
  }

  const buildTool = useMaven ? "Maven" : "Gradle";
  const ctx: TaskContext = { modules: [] };

  const baseTitle = `Resolving ${buildTool} dependencies`;
  const extractFn = useMaven ? extractMaven : extractGradle;

  const tasks = new Listr<TaskContext>(
    [
      {
        title: baseTitle,
        task: (ctx, task) =>
          withTimer(baseTitle, task, () => extractFn(ctx, task)),
      },
      {
        title: "Writing .metals/mbt.json",
        task: (ctx, task) => {
          const mbtBuild: MbtBuild = { dependencyModules: ctx.modules };
          mkdirSync(join(cwd, ".metals"), { recursive: true });
          writeFileSync(
            join(cwd, ".metals", "mbt.json"),
            JSON.stringify(mbtBuild, null, 2)
          );
          task.title = `Wrote ${ctx.modules.length} dependencies to .metals/mbt.json`;
        },
      },
    ],
    {
      concurrent: false,
      rendererOptions: { collapseSubtasks: false },
    }
  );

  try {
    await tasks.run(ctx);
  } catch (e) {
    console.error("\n" + (e as Error).message);
    if (ctx.buildOutput) {
      console.error("\n--- Build tool output ---");
      const lines = ctx.buildOutput.split("\n");
      console.error(lines.slice(-50).join("\n"));
    }
    process.exit(1);
  }
}

main();
