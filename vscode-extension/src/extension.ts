'use strict';

import * as path from 'path';
import { workspace, ExtensionContext, window } from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind
} from 'vscode-languageclient';
import { Requirements } from './requirements';

export async function activate(context: ExtensionContext) {
  const req = new Requirements();
  const javaHome = await req.getJavaHome().catch(pathNotFound => {
    window.showErrorMessage(pathNotFound);
  });

  const toolsJar = javaHome + '/lib/tools.jar';

  // The debug options for the server
  const debugOptions = [
    '-Xdebug',
    '-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000,quiet=y'
  ];

  // TODO(gabro): get this from the configuration
  // const logLevel = workspace.getConfiguration().get('scalaLanguageServer.logLevel')
  const logLevel = 'DEBUG';

  const coursierPath = path.join(context.extensionPath, './coursier');

  const coursierArgs = [
    'launch',
    '-r',
    'https://dl.bintray.com/dhpcs/maven',
    '-r',
    'sonatype:releases',
    '-J',
    toolsJar,
    'org.scalameta:language-server_2.12:0.1-SNAPSHOT',
    '-M',
    'scala.meta.languageserver.Main',
    '--',
    '--scalafmt-classpath',
    '/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.2/scala-library-2.12.2.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/trees_2.12/1.7.0/trees_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/trueaccord/scalapb/scalapb-runtime_2.12/0.6.0-pre2/scalapb-runtime_2.12-0.6.0-pre2.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.2.0/protobuf-java-3.2.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/tokens_2.12/1.7.0/tokens_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/inputs_2.12/1.7.0/inputs_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/geirsson/scalafmt-core_2.12/1.3.0/scalafmt-core_2.12-1.3.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/trueaccord/lenses/lenses_2.12/0.4.10/lenses_2.12-0.4.10.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/geirsson/metaconfig-typesafe-config_2.12/0.4.0/metaconfig-typesafe-config_2.12-0.4.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/tokenizers_2.12/1.7.0/tokenizers_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/geirsson/scalafmt-cli_2.12/1.3.0/scalafmt-cli_2.12-1.3.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/lihaoyi/fastparse-utils_2.12/0.4.2/fastparse-utils_2.12-0.4.2.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/common_2.12/1.7.0/common_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/semantic_2.12/1.7.0/semantic_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/martiansoftware/nailgun-server/0.9.1/nailgun-server-0.9.1.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/parsers_2.12/1.7.0/parsers_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/lihaoyi/scalaparse_2.12/0.4.2/scalaparse_2.12-0.4.2.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/quasiquotes_2.12/1.7.0/quasiquotes_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/typesafe/config/1.2.1/config-1.2.1.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/inline_2.12/1.7.0/inline_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/io_2.12/1.7.0/io_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/lihaoyi/fastparse_2.12/0.4.2/fastparse_2.12-0.4.2.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/geirsson/metaconfig-core_2.12/0.4.0/metaconfig-core_2.12-0.4.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/transversers_2.12/1.7.0/transversers_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.12/0.1.3/sourcecode_2.12-0.1.3.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/dialects_2.12/1.7.0/dialects_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scalameta/scalameta_2.12/1.7.0/scalameta_2.12-1.7.0.jar:/Users/ollie/.coursier/cache/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.12/3.5.0/scopt_2.12-3.5.0.jar'
  ];

  const javaArgs = [
    `-Dvscode.workspace=${workspace.rootPath}`,
    `-Dvscode.logLevel=${logLevel}`,
    '-jar',
    coursierPath
  ].concat(coursierArgs);

  const serverOptions: ServerOptions = {
    run: { command: 'java', args: javaArgs },
    debug: { command: 'java', args: debugOptions.concat(javaArgs) }
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: ['scala'],
    synchronize: {
      fileEvents: workspace.createFileSystemWatcher('**/*.semanticdb')
    }
  };

  const disposable = new LanguageClient(
    'scalameta',
    'Scalameta',
    serverOptions,
    clientOptions
  ).start();

  context.subscriptions.push(disposable);
}
