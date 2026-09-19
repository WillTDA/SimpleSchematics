param(
    [string]$ClasspathFile = '',
    [string]$JavaBin = ''
)

# After compileJava, run .\scripts\check_print_placement.ps1.
# To generate a fresh classpath without launching Minecraft:
# .\gradlew.bat --offline --init-script scripts/print_test_classpath.gradle writePrintTestClasspath
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repositoryRoot
try {
    if ([string]::IsNullOrEmpty($ClasspathFile)) {
        $ClasspathFile = Join-Path $repositoryRoot 'build/verification/print/runtime-classpath.txt'
}
    if (!(Test-Path -LiteralPath $ClasspathFile)) {
        throw 'Generate the runtime classpath using the Gradle command documented at the top of this script.'
    }
    if (!(Test-Path -LiteralPath 'build/classes/java/main')) {
        throw 'Run compileJava before these checks.'
    }
    $javaCompiler = if ([string]::IsNullOrEmpty($JavaBin)) { 'javac' } else { Join-Path $JavaBin 'javac.exe' }
    $javaRuntime = if ([string]::IsNullOrEmpty($JavaBin)) { 'java' } else { Join-Path $JavaBin 'java.exe' }
    $outputDirectory = Join-Path $repositoryRoot 'build/verification/print-tests'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $classpath = ((Join-Path $repositoryRoot 'build/classes/java/main') + ';' +
        (Join-Path $repositoryRoot 'build/resources/main') + ';' + ((Get-Content -LiteralPath $ClasspathFile) -join ';')).Replace('\', '/')
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $compileArguments = @('--release', '17', '-proc:none', '-classpath', ('"' + $classpath + '"'), '-d',
        ('"' + $outputDirectory.Replace('\', '/') + '"'), 'scripts/PrintPlacementTest.java',
        'scripts/CreativePrintTest.java', 'scripts/PrintBudgetTest.java', 'scripts/PrintTestBootstrap.java',
        'scripts/ConfigPersistenceTest.java')
    $compileFile = Join-Path $outputDirectory 'compile.args'
    [System.IO.File]::WriteAllLines($compileFile, $compileArguments, $utf8)
    & $javaCompiler "@$compileFile"
    if ($LASTEXITCODE -ne 0) { throw "Test compilation failed with exit code $LASTEXITCODE." }
    Push-Location -LiteralPath $outputDirectory
    try {
    foreach ($testClass in @('dev.willtda.simpleschematics.printing.PrintPlacementTest',
            'dev.willtda.simpleschematics.printing.CreativePrintTest', 'PrintBudgetTest',
            'dev.willtda.simpleschematics.printing.ConfigPersistenceTest')) {
        $runArguments = @('-classpath', ('"' + $outputDirectory.Replace('\', '/') + ';' + $classpath + '"'), $testClass)
        $runFile = Join-Path $outputDirectory 'run.args'
        [System.IO.File]::WriteAllLines($runFile, $runArguments, $utf8)
        & $javaRuntime "@$runFile"
        if ($LASTEXITCODE -ne 0) { throw "$testClass failed with exit code $LASTEXITCODE." }
    }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
