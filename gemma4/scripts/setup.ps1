param(
    [string]$PythonCommand = "py",
    [ValidateSet("cpu", "cu128")]
    [string]$ComputePlatform = "cpu"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path ".venv")) {
    & $PythonCommand -m venv .venv
}

$venvPython = Join-Path ".venv" "Scripts\python.exe"

if (-not (Test-Path $venvPython)) {
    throw "Virtual environment was not created correctly: $venvPython not found."
}

try {
    & $venvPython -m pip --version | Out-Null
} catch {
    Write-Host "pip is missing in the virtual environment. Attempting bootstrap with ensurepip..."
    & $venvPython -Im ensurepip --upgrade --default-pip
}

& $venvPython -m pip --version | Out-Null
if ($ComputePlatform -eq "cu128") {
    Write-Host "Installing CUDA-enabled PyTorch wheel (cu128)..."
    & $venvPython -m pip install --upgrade --index-url https://download.pytorch.org/whl/cu128 torch
} else {
    Write-Host "Installing CPU PyTorch wheel..."
    & $venvPython -m pip install --upgrade torch
}

& $venvPython -m pip install -r requirements.txt

Write-Host ""
Write-Host "Setup complete."
Write-Host "Next steps:"
Write-Host "1. Copy .env.example to .env and fill HF_TOKEN if needed."
Write-Host "2. Run: .\.venv\Scripts\python.exe .\gemma_cli.py --help"
