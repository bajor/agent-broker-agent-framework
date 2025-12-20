#!/bin/bash
# Launch distributed agents in separate Ghostty tabs
# Usage: ./scripts/run-distributed.sh [prompt-key]
# prompt-key: fibonacci, prime, factorial, sort, palindrome (default: prime)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PROMPT_KEY="${1:-prime}"

cd "$PROJECT_DIR"

echo "========================================================================"
echo "DISTRIBUTED AGENT PIPELINE"
echo "========================================================================"
echo "Project: $PROJECT_DIR"
echo "Prompt:  $PROMPT_KEY"
echo "========================================================================"

# Check if RabbitMQ is running
if ! docker ps --format '{{.Names}}' | grep -q '^rabbitmq$'; then
    echo "[1/6] Starting RabbitMQ..."
    make rabbit
    echo "Waiting for RabbitMQ to start..."
    sleep 5
else
    echo "[1/6] RabbitMQ already running"
fi

# Build the project first
echo "[2/6] Building project..."
make build

echo "[3/6] Launching agents in separate tabs..."

# Launch each agent in a new Ghostty tab using AppleScript
osascript <<EOF
tell application "Ghostty"
    activate

    -- Launch Preprocessor in new tab
    tell application "System Events"
        keystroke "t" using command down
        delay 0.3
    end tell
    delay 0.2
    tell application "System Events"
        keystroke "cd '$PROJECT_DIR' && make run-preprocessor"
        keystroke return
    end tell

    -- Launch CodeGen in new tab
    tell application "System Events"
        keystroke "t" using command down
        delay 0.3
    end tell
    delay 0.2
    tell application "System Events"
        keystroke "cd '$PROJECT_DIR' && make run-codegen"
        keystroke return
    end tell

    -- Launch Explainer in new tab
    tell application "System Events"
        keystroke "t" using command down
        delay 0.3
    end tell
    delay 0.2
    tell application "System Events"
        keystroke "cd '$PROJECT_DIR' && make run-explainer"
        keystroke return
    end tell

    -- Launch Refiner in new tab
    tell application "System Events"
        keystroke "t" using command down
        delay 0.3
    end tell
    delay 0.2
    tell application "System Events"
        keystroke "cd '$PROJECT_DIR' && make run-refiner"
        keystroke return
    end tell
end tell
EOF

echo "[4/6] Waiting for agents to initialize..."
sleep 8

echo "[5/6] Submitting prompt: $PROMPT_KEY"
make send-prompt PROMPT="$PROMPT_KEY"

echo ""
echo "========================================================================"
echo "DONE - Check the Refiner tab for final output"
echo "========================================================================"
echo ""
echo "To submit more prompts:"
echo "  make send-prompt PROMPT=fibonacci"
echo "  make send-prompt PROMPT=prime"
echo "  make send-prompt PROMPT=factorial"
echo "  make send-prompt PROMPT=sort"
echo "  make send-prompt PROMPT=palindrome"
echo ""
