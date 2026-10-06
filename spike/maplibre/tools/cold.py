import re, statistics, sys
for p in sys.argv[1:]:
    t = open(p).read()
    for k in ('TotalTime_ms', 'first_full_render_ms'):
        v = [int(x) for x in re.findall(k + r'=(\d+)', t)]
        print(p.split('/')[-1], k, 'n=%d' % len(v), 'mediana=%s' % statistics.median(v), 'min=%d max=%d' % (min(v), max(v)))
