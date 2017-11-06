import * as assert from 'assert';

import { window, Selection, Position, Uri, commands } from 'vscode';
import * as path from 'path';
import { execSync } from 'child_process';

const sleep = millis => new Promise(resolve => setTimeout(resolve, millis));

suite('Extension Tests', () => {
  const fixturePath = path.join(__dirname, '../../../test-workspace');
  const uri = Uri.file(
    path.join(fixturePath, 'src/main/scala/example/AnotherFile.scala')
  );

  suiteSetup(function() {
    this.timeout(Infinity);
    process.chdir(fixturePath);
    execSync('sbt compile');
  });

  test('Go to definition', async () => {
    const editor = await window.showTextDocument(uri);
    const bananaUseSelection = new Selection(
      new Position(6, 12),
      new Position(6, 18)
    );
    const bananaDeclarationPosition = new Position(2, 11);
    editor.selections = [bananaUseSelection];
    await sleep(2000);
    await commands.executeCommand('editor.action.goToDeclaration', uri);
    assert.equal(editor.selection.active, bananaDeclarationPosition);
  }).timeout(5000);
});
