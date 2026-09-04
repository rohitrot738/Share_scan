import threading
import time

import pytest

from execution.worker_pool import run_workers


def test_worker_count_never_exceeds_limit():
    lock=threading.Lock(); active=0; peak=0
    def worker(value):
        nonlocal active,peak
        with lock:active+=1; peak=max(peak,active)
        time.sleep(.01)
        with lock:active-=1
        return value
    report=run_workers(range(40),worker,max_workers=4)
    assert peak<=4 and report.max_workers==4 and report.successful==40


def test_hard_limit_caps_requested_workers():
    report=run_workers(range(12), lambda value:value, max_workers=8, hard_limit=3)
    assert report.max_workers==3
    assert report.results==list(range(12))


def test_empty_input_is_a_noop():
    report=run_workers([], lambda value:value)
    assert report.results==[]
    assert report.errors=={}
    assert report.submitted==0 and report.max_workers==0


def test_results_keep_input_order_despite_completion_order():
    def worker(value):
        time.sleep((5-value)*.002)
        return value*10
    report=run_workers(range(6),worker,max_workers=6)
    assert report.results==[0,10,20,30,40,50]


def test_one_worker_failure_does_not_cancel_others():
    def worker(value):
        if value==3:raise RuntimeError("bad stock")
        return value
    report=run_workers(range(7),worker,max_workers=3,item_key=str)
    assert report.results==[0,1,2,4,5,6]
    assert report.errors=={"3":"RuntimeError: bad stock"}


def test_invalid_worker_limits_fail_fast():
    with pytest.raises(ValueError):
        run_workers([1], lambda value:value, max_workers=0)
    with pytest.raises(ValueError):
        run_workers([1], lambda value:value, hard_limit=0)
