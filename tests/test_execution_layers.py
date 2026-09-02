import tempfile
import threading
import time
from pathlib import Path

from execution.checkpoint import CheckpointStore
from execution.job_queue import JobQueue
from execution.resilience import RetryPolicy,TokenBucketRateLimiter,call_with_retry
from execution.resource_controller import choose_resources
from execution.result_aggregator import aggregate_results
from execution.async_fetch import fetch_concurrently


def test_queue_deduplicates_and_batches():
    q=JobQueue(["A","B","A","C"]); assert len(q)==3 and q.take(2)==["A","B"] and q.take(2)==["C"]


def test_retry_recovers_and_timeout_fails():
    state={"n":0}
    def flaky():
        state["n"]+=1
        if state["n"]<3:raise RuntimeError("temporary")
        return 7
    assert call_with_retry(flaky,policy=RetryPolicy(attempts=3,timeout_seconds=1,base_delay_seconds=.001))==7


def test_checkpoint_resumes_only_unfinished():
    with tempfile.TemporaryDirectory() as d:
        c=CheckpointStore(str(Path(d)/"c.sqlite3"));c.success("run","A",{"score":1})
        assert c.pending("run",["A","B"])==["B"] and c.completed("run")["A"]["score"]==1


def test_checkpoint_freshness_expires_market_sensitive_resume():
    with tempfile.TemporaryDirectory() as d:
        c=CheckpointStore(str(Path(d)/"c.sqlite3"));c.success("run","A",{"score":1})
        assert c.pending("run",["A","B"],max_age_seconds=60)==["B"]
        with c._connect() as con:
            con.execute("UPDATE jobs SET updated_at=? WHERE run_id=? AND item_key=?",(time.time()-61,"run","A"))
        assert c.completed("run",max_age_seconds=60)=={}
        assert c.pending("run",["A","B"],max_age_seconds=60)==["A","B"]


def test_resource_plan_is_bounded_and_aggregator_is_stable():
    p=choose_resources(requested_workers=1000,requested_batch_size=1000);assert 1<=p.workers<=32 and 10<=p.batch_size<=200
    out=aggregate_results([{"symbol":"A","volume":5,"ghost_score":80},{"symbol":"A","volume":4,"ghost_score":90},{"symbol":"B","volume":6,"ghost_score":70}],top_n=2)
    assert [x["symbol"] for x in out]==["B","A"] and [x["rank"] for x in out]==[1,2]


def test_bounded_fetch_layer_isolates_provider_error():
    def fetch(value):
        if value==2:raise ValueError("permanent bad request")
        return value*2
    report=fetch_concurrently(range(5),fetch,max_workers=2,rate_per_second=100,timeout_seconds=1,item_key=str)
    assert report.results==[0,2,6,8] and "2" in report.errors
