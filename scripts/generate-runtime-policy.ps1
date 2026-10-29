param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{12}$')]
    [string]$ProjectId
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$generator = Join-Path $projectRoot '.gradle/deployment-tools/venv/Scripts/iam-policy-autopilot.exe'
if (-not (Test-Path -LiteralPath $generator)) {
    throw 'Install the approved project-local validation toolchain first.'
}
$sourceFiles = @(
    'src/main/java/com/recomate/user/infrastructure/DynamoDbUserRepository.java',
    'src/main/java/com/recomate/user/infrastructure/DynamoDbSessionDataEraser.java',
    'src/main/java/com/recomate/user/infrastructure/CognitoIdentityDirectory.java',
    'src/main/java/com/recomate/session/repository/DynamoSessionRepository.java'
) | ForEach-Object { (Resolve-Path -LiteralPath (Join-Path $projectRoot $_)).Path }
$outputDirectory = Join-Path $projectRoot '.gradle/deployment'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$outputFile = Join-Path $outputDirectory 'runtime-policy.raw.json'
# Equivalent to: uvx iam-policy-autopilot@latest generate-policies <absolute source paths>
# --account <project id> --region ap-southeast-2 --service-hints dynamodb cognito-idp --pretty
# Use the explicitly approved pinned executable. This generates a baseline only; never uploads it.
& $generator generate-policies @sourceFiles --account $ProjectId --region ap-southeast-2 `
    --service-hints dynamodb cognito-idp --pretty > $outputFile
if ($LASTEXITCODE -ne 0) { throw 'Application policy generation failed.' }
Get-Content -LiteralPath $outputFile -Raw | ConvertFrom-Json | Out-Null
Write-Output 'Generated runtime-policy.raw.json; scope and review it before applying to IAM.'
