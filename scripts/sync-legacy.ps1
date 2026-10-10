$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskJava = Join-Path $taskRoot 'src/main/java/com/example/accountswitcher'
$taskLegacy = Join-Path $taskRoot 'legacy-1.21.11/src/main/java/com/example/accountswitcher'
foreach ($taskFile in @('auth/Account.java', 'auth/AccountStorage.java', 'auth/AccountQuery.java')) {
    Copy-Item -LiteralPath (Join-Path $taskJava $taskFile) -Destination (Join-Path $taskLegacy $taskFile)
}
foreach ($taskFile in @('gui/AccountSwitcherScreen.java', 'gui/AccountListWidget.java', 'gui/KeychainUi.java', 'gui/OfflineAccountScreen.java', 'gui/AddMicrosoftScreen.java', 'gui/ConfirmRemoveScreen.java')) {
    $taskBody = Get-Content -LiteralPath (Join-Path $taskJava $taskFile) -Raw
    $taskBody = $taskBody.Replace('PlayerFaceExtractor.extractRenderState', 'PlayerFaceRenderer.draw')
    $taskBody = $taskBody.Replace('PlayerFaceExtractor', 'PlayerFaceRenderer').Replace('GuiGraphicsExtractor', 'GuiGraphics')
    $taskBody = $taskBody.Replace('extractRenderState', 'render').Replace('extractBackground', 'renderBackground')
    $taskBody = $taskBody.Replace('extractContent', 'renderContent').Replace('g.centeredText(', 'g.drawCenteredString(')
    $taskBody = $taskBody.Replace('g.text(', 'g.drawString(').Replace('setScreenAndShow(', 'setScreen(')
    [IO.File]::WriteAllText((Join-Path $taskLegacy $taskFile), $taskBody)
}
$taskMetadata = Join-Path $taskRoot 'legacy-1.21.11/src/main/resources/fabric.mod.json'
$taskBody = (Get-Content -LiteralPath $taskMetadata -Raw).Replace('"version": "2.0"', '"version": "${version}"').Replace('Keychain 2.0', 'Keychain')
[IO.File]::WriteAllText($taskMetadata, $taskBody)
$taskTestDir = Join-Path $taskRoot 'legacy-1.21.11/src/test/java/com/example/accountswitcher/auth'
New-Item -ItemType Directory -Path $taskTestDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'src/test/java/com/example/accountswitcher/auth/AccountStorageTest.java'), (Join-Path $taskRoot 'src/test/java/com/example/accountswitcher/auth/AccountQueryTest.java') -Destination $taskTestDir
