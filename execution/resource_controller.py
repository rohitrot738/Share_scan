from __future__ import annotations

import os
from dataclasses import dataclass


def available_memory_mb()->int|None:
    try:
        with open("/proc/meminfo",encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):return int(line.split()[1])//1024
    except OSError:pass
    return None


@dataclass(frozen=True)
class ResourcePlan:
    workers:int
    batch_size:int
    cpu_count:int
    available_memory_mb:int|None


def choose_resources(*,requested_workers:int=8,requested_batch_size:int=50)->ResourcePlan:
    cpu=max(1,os.cpu_count() or 1); memory=available_memory_mb()
    workers=max(1,min(requested_workers,32,max(2,cpu*2)))
    batch=max(10,min(requested_batch_size,200))
    if memory is not None and memory<1024:workers=min(workers,2);batch=min(batch,20)
    elif memory is not None and memory<2048:workers=min(workers,4);batch=min(batch,30)
    return ResourcePlan(workers,batch,cpu,memory)
