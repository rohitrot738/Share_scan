from __future__ import annotations

import time
from collections import Counter
from contextlib import contextmanager


class ScanMetrics:
    def __init__(self):self.counts=Counter();self.timings={};self.started=time.perf_counter()
    def count(self,name:str,value:int=1):self.counts[name]+=value
    @contextmanager
    def timer(self,name:str):
        start=time.perf_counter()
        try:yield
        finally:self.timings[name]=self.timings.get(name,0.0)+time.perf_counter()-start
    def snapshot(self):return {"runtime_seconds":round(time.perf_counter()-self.started,3),"counts":dict(self.counts),"stage_seconds":{k:round(v,3) for k,v in self.timings.items()}}
