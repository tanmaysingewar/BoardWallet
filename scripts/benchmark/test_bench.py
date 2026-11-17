"""Offline tests for percentile math and accounting; never connects to MySQL."""
import copy
import unittest
from decimal import Decimal
from unittest.mock import patch

import bench


def snapshot(count=0, buckets=()):
    return {'digests':[{'DIGEST':'query','DIGEST_TEXT':'SELECT ?', 'COUNT_STAR':count,
        'SUM_TIMER_WAIT':count*1000000000,'SUM_ROWS_EXAMINED':count,'SUM_ROWS_SENT':count,
        'SUM_ERRORS':0,'SUM_NO_INDEX_USED':0}],
        'histograms':[{'DIGEST':'query','BUCKET_NUMBER':i,'BUCKET_TIMER_HIGH':upper*1000000000,
                       'BUCKET_TIMER_LOW':0,'COUNT_BUCKET':n} for i,upper,n in buckets],
        'status':{'Uptime':100},'errors':{}}


class MetricsTests(unittest.TestCase):
    def test_percentiles_use_interval_counts_not_lifetime_counts(self):
        before=snapshot(1000,[(0,1,1000)])
        after=snapshot(1100,[(0,1,1000),(1,10,95),(2,20,4),(3,30,1)])
        q=bench.database_delta(before,after)['queries'][0]
        self.assertEqual(q['executions'],100)
        self.assertEqual(q['p95_upper_bound_ms'],10)
        self.assertEqual(q['p99_upper_bound_ms'],20)
        self.assertEqual(q['mean_ms'],1)

    def test_reset_is_not_reported_as_valid_latency(self):
        with self.assertRaises(RuntimeError):
            bench.database_delta(snapshot(100,[(0,1,100)]),snapshot(1,[(0,1,1)]))

    def test_missing_histogram_does_not_invent_zero_latency(self):
        q=bench.database_delta(snapshot(),snapshot(10))['queries'][0]
        self.assertIsNone(q['p95_upper_bound_ms'])

    def test_negative_balance_is_not_hidden_by_total_conservation(self):
        a='00000000-0000-4000-8000-000000000001'
        b='00000000-0000-4000-8000-000000000002'
        tx=[{'from_wallet_id':bench.binary(a),'to_wallet_id':bench.binary(b),
             'amount':Decimal('11'),'initiated_by':b'actor','idempotency_key':b'key'}]
        with patch.object(bench,'balances',return_value={a:Decimal('-1'),b:Decimal('21')}),patch.object(bench,'rows',return_value=tx):
            result=bench.reconcile(None,{a:Decimal('10'),b:Decimal('10')},'prefix','hot',0)
            self.assertFalse(result['passed'])

    def test_duplicate_success_response_requires_one_database_effect(self):
        with patch.object(bench,'balances',return_value={}),patch.object(bench,'rows',return_value=[]):
            result=bench.reconcile(None,{},'prefix','idempotency',0)
            self.assertFalse(result['passed'])


if __name__=='__main__':
    unittest.main()
