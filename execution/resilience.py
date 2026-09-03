from __future__ import annotations

import queue
import random
import threading
import time
from dataclasses import dataclass
from typing import Callable, TypeVar

R = TypeVar("R")


class TokenBucketRateLimiter:
    def __init__(self, rate_per_second: float = 4.0, burst: int = 4):
        if rate_per_second <= 0 or burst < 1:
            raise ValueError("rate_per_second and burst must be positive")
        self.rate = float(rate_per_second)
        self.capacity = float(burst)
        self.tokens = float(burst)
        self.updated = time.monotonic()
        self.lock = threading.Lock()

    def acquire(self) -> None:
        while True:
            with self.lock:
                now = time.monotonic()
                self.tokens = min(self.capacity, self.tokens + (now - self.updated) * self.rate)
                self.updated = now
                if self.tokens >= 1:
                    self.tokens -= 1
                    return
                delay = (1 - self.tokens) / self.rate
            time.sleep(min(delay, 1.0))


@dataclass(frozen=True)
class RetryPolicy:
    attempts: int = 3
    timeout_seconds: float = 30.0
    base_delay_seconds: float = 0.5
    max_delay_seconds: float = 4.0

    def __post_init__(self):
        if self.attempts < 1 or self.timeout_seconds <= 0:
            raise ValueError("attempts and timeout_seconds must be positive")


def _call_with_timeout(function: Callable[[], R], timeout: float) -> R:
    output: queue.Queue = queue.Queue(maxsize=1)
    def target():
        try: output.put((True, function()))
        except BaseException as exc: output.put((False, exc))
    thread = threading.Thread(target=target, daemon=True, name="share-scan-timeout")
    thread.start(); thread.join(timeout)
    if thread.is_alive():
        raise TimeoutError(f"operation exceeded {timeout:.2f}s")
    ok, value = output.get_nowait()
    if ok:return value
    raise value


def call_with_retry(
    function: Callable[[], R], *, policy: RetryPolicy | None = None,
    limiter: TokenBucketRateLimiter | None = None,
    retry_on: tuple[type[BaseException], ...] = (TimeoutError, ConnectionError, RuntimeError),
) -> R:
    policy = policy or RetryPolicy()
    last: BaseException | None = None
    for attempt in range(policy.attempts):
        if limiter is not None: limiter.acquire()
        try:return _call_with_timeout(function, policy.timeout_seconds)
        except retry_on as exc:last=exc
        if attempt + 1 < policy.attempts:
            delay=min(policy.max_delay_seconds,policy.base_delay_seconds*(2**attempt))
            time.sleep(delay+random.uniform(0,min(.1,delay/5)))
    assert last is not None
    raise last
