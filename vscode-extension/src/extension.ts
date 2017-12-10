'use strict';

import * as path from 'path';
import { workspace, ExtensionContext, window, commands } from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
  RevealOutputChannelOn
} from 'vscode-languageclient';
import { Requirements } from './requirements';
import { exec } from 'child_process';

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

  // TODO(gabro): get this from th configuration
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
    'org.scalameta:metaserver_2.12:0.1-SNAPSHOT',
    '-M',
    'scala.meta.languageserver.Main'
  ];

  const javaArgs = [
    `-Dvscode.workspace=${workspace.rootPath}`,
    `-Dvscode.logLevel=${logLevel}`,
    `-XX:+UseG1GC`,
    `-XX:+UseStringDeduplication`,
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
      fileEvents: [
        workspace.createFileSystemWatcher('**/*.semanticdb'),
        workspace.createFileSystemWatcher('**/*.compilerconfig')
      ]
    },
    revealOutputChannelOn: RevealOutputChannelOn.Never
  };

  const client = new LanguageClient(
    'scalameta',
    'Scalameta',
    serverOptions,
    clientOptions
  );

  const restartServerCommand = commands.registerCommand("scalameta.restartServer", async () => {
    await exec("jps | grep coursier | awk '{ print $1 }' | xargs kill");
    const showLogsAction = "Show server logs";
    const selectedAction = await window.showInformationMessage(
      "Scalameta Language Server killed, it should restart in a few seconds",
      showLogsAction
    );
    const myOutputChannel = window.createOutputChannel('Scalameta');

    if (selectedAction === showLogsAction) {
      client.outputChannel.show(true);
    }
  });

  context.subscriptions.push(client.start(), restartServerCommand);
}
