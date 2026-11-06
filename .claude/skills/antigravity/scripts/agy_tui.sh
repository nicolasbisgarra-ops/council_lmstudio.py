#!/bin/bash
# Abre (si no está) la TUI de agy en tmux «agy»; hace falta para /usage.
export PATH=$HOME/.local/bin:$PATH
tmux has-session -t agy 2>/dev/null && exit 0
tmux new-session -d -s agy -x 200 -y 50 -c "$(git rev-parse --show-toplevel)" agy
for _ in $(seq 20); do tmux capture-pane -pt agy | grep -q "for shortcuts" && exit 0; timeout 1 tail -f /dev/null; done
echo "agy no arrancó; mirar: tmux attach -t agy" >&2; exit 1
