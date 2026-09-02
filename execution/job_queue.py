from __future__ import annotations

import threading
from collections import deque
from typing import Generic, Iterable, TypeVar

T=TypeVar("T")


class JobQueue(Generic[T]):
    def __init__(self,items:Iterable[T]=(),*,key=str):
        self._key=key; self._items=deque(); self._seen=set(); self._lock=threading.Lock()
        self.extend(items)
    def extend(self,items:Iterable[T])->int:
        added=0
        with self._lock:
            for item in items:
                k=self._key(item)
                if k in self._seen:continue
                self._seen.add(k); self._items.append(item); added+=1
        return added
    def take(self,count:int)->list[T]:
        if count<1:raise ValueError("count must be positive")
        with self._lock:
            return [self._items.popleft() for _ in range(min(count,len(self._items)))]
    def __len__(self):
        with self._lock:return len(self._items)
