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
    """Execute a bounded CPU/network worker pool and preserve input order."""
    if max_workers < 1:
        raise ValueError("max_workers must be at least 1")
    if hard_limit < 1:
        raise ValueError("hard_limit must be at least 1")
    values = list(items)
    worker_count = min(max_workers, hard_limit, max(1, len(values)))
    report: WorkerPoolReport[R] = WorkerPoolReport(submitted=len(values), max_workers=worker_count)
    if not values:
        report.max_workers = 0
        return report

    started = time.perf_counter()
    ordered: dict[int, R] = {}
    with ThreadPoolExecutor(max_workers=worker_count, thread_name_prefix="share-scan") as pool:
        futures = {pool.submit(worker, item): (index, item) for index, item in enumerate(values)}
        for future in as_completed(futures):
            index, item = futures[future]
            try:
                ordered[index] = future.result()
            except Exception as exc:
                report.errors[item_key(item)] = f"{type(exc).__name__}: {exc}"
    report.results = [ordered[index] for index in sorted(ordered)]
    report.successful = len(report.results)
    report.failed = len(report.errors)
    report.runtime_seconds = time.perf_counter() - started
    return report
