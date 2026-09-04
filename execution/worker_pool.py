from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Callable, Generic, Iterable, TypeVar

T = TypeVar("T")
R = TypeVar("R")


@dataclass
class WorkerPoolReport(Generic[R]):
    results: list[R] = field(default_factory=list)
    errors: dict[str, str] = field(default_factory=dict)
    submitted: int = 0
    successful: int = 0
    failed: int = 0
    max_workers: int = 0
    runtime_seconds: float = 0.0


def run_workers(
    items: Iterable[T],
    worker: Callable[[T], R],
    *,
    max_workers: int = 8,
    hard_limit: int = 32,
    item_key: Callable[[T], str] = str,
) -> WorkerPoolReport[R]:
    """Execute a bounded worker pool while limiting in-flight futures.

    Results retain input order. Worker failures are isolated to individual
    items. Only a small submission window is kept in flight so a large NSE
    universe does not create hundreds/thousands of queued Future objects.
    """
    if max_workers < 1:
        raise ValueError("max_workers must be at least 1")
    if hard_limit < 1:
        raise ValueError("hard_limit must be at least 1")

    values = list(items)
    if not values:
        return WorkerPoolReport(max_workers=0)

    worker_count = min(max_workers, hard_limit, len(values))
    report: WorkerPoolReport[R] = WorkerPoolReport(
        submitted=len(values), max_workers=worker_count
    )
    started = time.perf_counter()
    ordered: dict[int, R] = {}
    next_index = 0

    with ThreadPoolExecutor(max_workers=worker_count, thread_name_prefix="share-scan") as pool:
        # Keep at most this many futures queued/running. This bounds scheduler
        # memory while preserving throughput for network-heavy scan stages.
        window = max(worker_count, worker_count * 2)
        active: dict = {}

        def submit_one(index: int) -> None:
            future = pool.submit(worker, values[index])
            active[future] = (index, values[index])

        while next_index < len(values) and len(active) < window:
            submit_one(next_index)
            next_index += 1

        while active:
            # as_completed over a snapshot lets us refill the window after each
            # completed future without ever exceeding the configured bound.
            for future in as_completed(list(active)):
                index, item = active.pop(future)
                try:
                    ordered[index] = future.result()
                except Exception as exc:
                    report.errors[item_key(item)] = f"{type(exc).__name__}: {exc}"

                if next_index < len(values):
                    submit_one(next_index)
                    next_index += 1
                break

    report.results = [ordered[index] for index in sorted(ordered)]
    report.successful = len(report.results)
    report.failed = len(report.errors)
    report.runtime_seconds = time.perf_counter() - started
    return report
