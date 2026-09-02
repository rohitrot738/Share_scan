from __future__ import annotations

from typing import Callable,Iterable,TypeVar

from .resilience import RetryPolicy,TokenBucketRateLimiter,call_with_retry
from .worker_pool import WorkerPoolReport,run_workers

T=TypeVar("T");R=TypeVar("R")


def fetch_concurrently(jobs:Iterable[T],fetcher:Callable[[T],R],*,max_workers:int=3,rate_per_second:float=2.0,timeout_seconds:float=90.0,item_key:Callable[[T],str]=str)->WorkerPoolReport[R]:
    """Bounded network fetch layer with shared throttling, timeout and retry."""
    limiter=TokenBucketRateLimiter(rate_per_second=rate_per_second,burst=max(1,max_workers))
    policy=RetryPolicy(attempts=3,timeout_seconds=timeout_seconds,base_delay_seconds=.5,max_delay_seconds=4)
    return run_workers(jobs,lambda job:call_with_retry(lambda:fetcher(job),policy=policy,limiter=limiter),max_workers=max_workers,hard_limit=8,item_key=item_key)
