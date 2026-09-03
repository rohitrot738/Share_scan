from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable, Generic, Iterable, TypeVar

T = TypeVar("T")
R = TypeVar("R")


def iter_batches(items: Iterable[T], batch_size: int) -> Iterable[list[T]]:
    """Yield bounded lists without consuming the whole input in memory."""
    if batch_size < 1:
        raise ValueError("batch_size must be at least 1")
    batch: list[T] = []
    for item in items:
        batch.append(item)
        if len(batch) == batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


@dataclass
class BatchReport(Generic[R]):
    results: list[R] = field(default_factory=list)
    errors: dict[str, str] = field(default_factory=dict)
    total_items: int = 0
    successful_items: int = 0
    failed_items: int = 0
    completed_batches: int = 0
    runtime_seconds: float = 0.0


def run_batches(
    items: Iterable[T],
    processor: Callable[[list[T]], tuple[list[R], dict[str, str]]],
    *,
    batch_size: int = 50,
    item_key: Callable[[T], str] = str,
    on_batch_complete: Callable[[int, BatchReport[R]], None] | None = None,
) -> BatchReport[R]:
    """Run isolated batches and aggregate results in deterministic batch order.

    A whole-batch exception is converted into per-item errors, allowing later
    batches to continue instead of aborting a 1000-stock scan.
    """
    report: BatchReport[R] = BatchReport()
    started = time.perf_counter()
    for batch_no, batch in enumerate(iter_batches(items, batch_size), 1):
        report.total_items += len(batch)
        try:
            results, errors = processor(batch)
            report.results.extend(results)
            report.errors.update(errors)
        except Exception as exc:
            reason = f"{type(exc).__name__}: {exc}"
            report.errors.update({item_key(item): reason for item in batch})
        report.completed_batches = batch_no
        report.successful_items = len(report.results)
        report.failed_items = len(report.errors)
        if on_batch_complete is not None:
            on_batch_complete(batch_no, report)
    report.runtime_seconds = time.perf_counter() - started
    return report
