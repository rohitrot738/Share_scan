from execution.batch_engine import iter_batches, run_batches


def test_iter_batches_keeps_order_and_bounds_size():
    batches=list(iter_batches(range(103),25))
    assert [len(x) for x in batches]==[25,25,25,25,3]
    assert [x for batch in batches for x in batch]==list(range(103))


def test_failed_batch_does_not_stop_later_batches():
    def processor(batch):
        if 3 in batch:raise RuntimeError("provider down")
        return [x*10 for x in batch],{}
    report=run_batches(range(7),processor,batch_size=3,item_key=str)
    assert report.results==[0,10,20,60]
    assert set(report.errors)=={"3","4","5"}
    assert report.completed_batches==3


def test_item_errors_are_aggregated():
    def processor(batch):
        return [x for x in batch if x%2==0],{str(x):"bad" for x in batch if x%2}
    report=run_batches(range(10),processor,batch_size=4,item_key=str)
    assert report.results==[0,2,4,6,8]
    assert report.successful_items==5 and report.failed_items==5
