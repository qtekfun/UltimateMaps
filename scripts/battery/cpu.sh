#!/system/bin/sh
# usage: cpu.sh <pkg> <seconds>   per-thread CPU (% of one core) and wakeups/s (voluntary context switches) over the interval
pid=$(pidof $1); secs=$2
snap() { for t in /proc/$pid/task/*; do tid=${t##*/}; v=$(grep voluntary_ctxt $t/status | head -1 | tr -dc 0-9); echo "$tid $(cat $t/comm | tr ' ' '_') $(cut -d' ' -f14,15 $t/stat) $v"; done; }
f0=$(dumpsys window | grep mCurrentFocus | cut -d' ' -f5)
snap > /data/local/tmp/a.txt
sleep $secs
snap > /data/local/tmp/b.txt
f1=$(dumpsys window | grep mCurrentFocus | cut -d' ' -f5)
echo "focus: $f0 -> $f1"
awk -v s=$secs 'NR==FNR{a[$1]=$3+$4; w[$1]=$5; next} {if($1 in a){d=($3+$4)-a[$1]; tot+=d; n[$2]+=d; sw[$2]+=($5-w[$1])}} END{for(k in n) if(n[k]>0) printf "%6.1f%% %5.0f wk/s %s\n", n[k]/s, sw[k]/s, k; printf "%6.1f%% TOTAL\n", tot/s}' /data/local/tmp/a.txt /data/local/tmp/b.txt | sort -rn | head -8
