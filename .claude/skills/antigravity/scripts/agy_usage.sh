#!/bin/bash
# Imprime la cuota restante de cada pool (semanal y 5 h).
"$(dirname "$0")/agy_tui.sh" || exit 1
tmux send-keys -t agy Escape; tmux send-keys -t agy C-u
tmux send-keys -t agy -l "/usage"; timeout 1 tail -f /dev/null; tmux send-keys -t agy Enter
for _ in $(seq 15); do tmux capture-pane -pt agy | grep -q "Weekly Limit" && break; timeout 1 tail -f /dev/null; done
tmux capture-pane -pt agy -S -100 | grep -E "^(GEMINI|CLAUDE)|Limit|%|Refreshes|available" | sed 's/\[[█░]*\] *//'
tmux send-keys -t agy Escape
