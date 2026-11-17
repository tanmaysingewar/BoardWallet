#!/usr/bin/env python3
"""Local-only benchmark preparation, execution, DB telemetry and reconciliation."""
import argparse
import base64
import hashlib
import hmac
import json
import math
import os
import platform
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import psutil
import pymysql

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
from seed_dataset import connect, save

HOME_DATA = Path.home() / '.local/share/boardwallet-seed'
SEED_DATA = HOME_DATA / 'full-20261116'
BENCH = HOME_DATA / 'benchmark'
MODES = ('smoke','read','mixed','hot','idempotency','conflict','overspend','security','login','spike','soak')


def rows(db, sql, args=None):
    with db.cursor(pymysql.cursors.DictCursor) as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def binary(value):
    return uuid.UUID(value).bytes


def text_id(value):
    return str(uuid.UUID(bytes=value))


def prepare():
    BENCH.mkdir(parents=True, exist_ok=True, mode=0o700)
    db = connect(PROJECT)
    users = [json.loads(line) for line in (SEED_DATA/'active-users.jsonl').read_text().splitlines()]
    assert len(users) == 10000 and len({u['user_id'] for u in users}) == 10000
    for i, user in enumerate(users):
        result = rows(db, '''SELECT w.id,w.user_id,u.username,b.status FROM wallet w
          JOIN user u ON u.id=w.user_id JOIN board b ON b.id=w.board_id
          WHERE w.board_id=%s AND w.wallet_type='PLAYER' AND w.id<>%s ORDER BY w.id LIMIT 1''',
                      (binary(user['board_id']), binary(user['wallet_id'])))
        if not result or result[0]['status'] != 'ACTIVE':
            raise RuntimeError('Fixture requires active boards with multiple players')
        recipient = result[0]
        user['recipient_wallet_id'] = text_id(recipient['id'])
        if i == 0:
            reverse = dict(user_id=text_id(recipient['user_id']), username=recipient['username'],
                           wallet_id=text_id(recipient['id']), board_id=user['board_id'],
                           recipient_wallet_id=user['wallet_id'])
        if (i+1) % 2000 == 0:
            print(f'Prepared {i+1:,} users', flush=True)
    hot = [users[0], reverse]
    outsider = next(u for u in users[1:] if u['board_id'] != hot[0]['board_id'] and not rows(db,
        'SELECT 1 FROM wallet WHERE board_id=%s AND user_id=%s',
        (binary(hot[0]['board_id']), binary(u['user_id']))))
    save(BENCH/'participants.json', {'users':users, 'hot':hot, 'outsider':outsider})
    # EXPLAIN only: execution plans are saved without running the queries themselves.
    u = users[0]
    queries = {
        'username': ('SELECT id FROM user WHERE username=%s', (u['username'],)),
        'membership': ('SELECT * FROM wallet WHERE board_id=%s AND user_id=%s',
                       (binary(u['board_id']),binary(u['user_id']))),
        'history_page': ('SELECT * FROM game_transaction WHERE board_id=%s ORDER BY created_at DESC,id DESC LIMIT 20',
                         (binary(u['board_id']),)),
        'idempotency': ('SELECT * FROM game_transaction WHERE initiated_by=%s AND idempotency_key=%s',
                        (binary(u['user_id']),uuid.uuid4().bytes))}
    plans = {}
    for name,(query,params) in queries.items():
        with db.cursor() as cur:
            cur.execute('EXPLAIN FORMAT=JSON ' + query, params)
            plans[name] = json.loads(cur.fetchone()[0])
            # New MySQL EXPLAIN formats may inline constant row values; retain plan only.
            plans[name].pop('query', None)
    save(BENCH/'query-plans.json', plans)
    db.close()
    probe = Monitor()
    snap = probe.snapshot()
    save(BENCH/'telemetry-preflight.json', {'performance_schema':True,
         'digest_rows':len(snap['digests']), 'histogram_buckets':len(snap['histograms']),
         'consumers':rows(probe.db, 'SELECT NAME,ENABLED FROM setup_consumers'),
         'sample':probe.sample(), 'note':'Preparation only, not benchmark results'})
    probe.db.close()
    print(f'Prepared fixture and read-only diagnostic probes in {BENCH}')


def jwt(subject, secret, expired=False):
    def enc(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
    now = int(time.time())
    claims = {'iss':'boardwallet','sub':subject,'iat':now-3600 if expired else now,
              'exp':now-120 if expired else now+900}
    content = enc(b'{"alg":"HS256","typ":"JWT"}') + '.' + enc(json.dumps(claims).encode())
    return content + '.' + enc(hmac.new(secret,content.encode(),hashlib.sha256).digest())


class Monitor:
    def __init__(self):
        self.db = connect(PROJECT)
        self.db.select_db('performance_schema')  # Keeps diagnostic statements out of app-schema digests.
        self.db.autocommit(True)
        consumers = {r['NAME']:r['ENABLED'] for r in rows(self.db,'SELECT NAME,ENABLED FROM setup_consumers')}
        required = ('global_instrumentation','thread_instrumentation','statements_digest','events_statements_current')
        if any(consumers.get(name)!='YES' for name in required):
            raise RuntimeError('Required Performance Schema consumers are disabled; telemetry would be incomplete')
        self.processes = {}
        psutil.cpu_percent()

    def snapshot(self):
        fields = 'DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_ERRORS,SUM_NO_INDEX_USED'
        return {
            'digests': rows(self.db, f'SELECT {fields} FROM events_statements_summary_by_digest WHERE SCHEMA_NAME=%s', ('boardwallet',)),
            'histograms': rows(self.db, '''SELECT DIGEST,BUCKET_NUMBER,BUCKET_TIMER_LOW,BUCKET_TIMER_HIGH,COUNT_BUCKET
              FROM events_statements_histogram_by_digest WHERE SCHEMA_NAME=%s AND COUNT_BUCKET>0''', ('boardwallet',)),
            'status': {r['VARIABLE_NAME']:int(r['VARIABLE_VALUE']) for r in rows(self.db,
                "SELECT VARIABLE_NAME,VARIABLE_VALUE FROM global_status WHERE VARIABLE_NAME IN ('Innodb_deadlocks','Innodb_row_lock_waits','Innodb_row_lock_time','Uptime','Performance_schema_digest_lost')")},
            'errors': {str(r['ERROR_NUMBER']):r['SUM_ERROR_RAISED'] for r in rows(self.db,
                'SELECT ERROR_NUMBER,SUM_ERROR_RAISED FROM events_errors_summary_global_by_error WHERE ERROR_NUMBER IN (1205,1213)')}
        }

    def sample(self):
        waits = rows(self.db, '''SELECT COUNT(*) AS waiting_transactions,
          COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,trx_wait_started,NOW(6)))/1000,0) AS oldest_current_wait_age_ms
          FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_mysql_thread_id IN
          (SELECT PROCESSLIST_ID FROM threads WHERE PROCESSLIST_DB='boardwallet')''')[0]
        for proc in psutil.process_iter(['pid','name']):
            if proc.info['name'] in ('java','mysqld','k6') and proc.pid not in self.processes:
                self.processes[proc.pid] = proc
                proc.cpu_percent()
        metrics = []
        for proc in list(self.processes.values()):
            try:
                metrics.append({'pid':proc.pid,'name':proc.name(), 'cpu_percent':proc.cpu_percent(),
                                'rss_bytes':proc.memory_info().rss})
            except (psutil.NoSuchProcess,psutil.AccessDenied):
                pass
        return {'utc':datetime.now(timezone.utc).isoformat(), **waits,
                'host_cpu_percent':psutil.cpu_percent(), 'available_memory_bytes':psutil.virtual_memory().available,
                'processes':metrics}


def database_delta(before, after):
    if after['status'].get('Uptime',0)<before['status'].get('Uptime',0):
        raise RuntimeError('Database restarted during measurement')
    baseline = {r['DIGEST']:r for r in before['digests']}
    if set(baseline)-{r['DIGEST'] for r in after['digests']}:
        raise RuntimeError('Digest entries disappeared during run; counters may have been reset')
    old_buckets = {(r['DIGEST'],r['BUCKET_NUMBER']):r['COUNT_BUCKET'] for r in before['histograms']}
    buckets = defaultdict(list)
    for row in after['histograms']:
        count = row['COUNT_BUCKET'] - old_buckets.get((row['DIGEST'],row['BUCKET_NUMBER']),0)
        if count < 0:
            raise RuntimeError('Histogram reset during run; interval percentiles invalid')
        if count:
            buckets[row['DIGEST']].append((row['BUCKET_TIMER_HIGH']/1e9,count))
    result = []
    for row in after['digests']:
        old = baseline.get(row['DIGEST'],{})
        n = row['COUNT_STAR']-old.get('COUNT_STAR',0)
        if n < 0:
            raise RuntimeError('Digest counters reset during run')
        if not n:
            continue
        record = {'digest':row['DIGEST'],'sql':row['DIGEST_TEXT'],'executions':n}
        for field in ('SUM_TIMER_WAIT','SUM_ROWS_EXAMINED','SUM_ROWS_SENT','SUM_ERRORS','SUM_NO_INDEX_USED'):
            record[field.lower()] = row[field]-old.get(field,0)
        record['mean_ms'] = record['sum_timer_wait']/n/1e9
        hist = sorted(buckets[row['DIGEST']])
        hist_n = sum(count for _,count in hist)
        record['histogram_samples'] = hist_n
        for q in (95,99):
            cumulative = 0
            record[f'p{q}_upper_bound_ms'] = None
            for upper,count in hist:
                cumulative += count
                if cumulative >= math.ceil(hist_n*q/100):
                    record[f'p{q}_upper_bound_ms'] = upper
                    break
        result.append(record)
    return {'queries':sorted(result,key=lambda x:x['sum_timer_wait'],reverse=True),
            'server_wide_counter_deltas':{k:after['status'].get(k,0)-v for k,v in before['status'].items()},
            'server_wide_error_deltas':{k:after['errors'].get(k,0)-v for k,v in before['errors'].items()},
            'notes':['Query percentiles are histogram bucket upper bounds over snapshot differences.',
                     'Digest snapshots are not atomic; inspect histogram sample counts for boundary differences.',
                     'No pure per-request row-lock p95/p99 is claimed. Wait ages are sampled ongoing waits.',
                     'Global counters include other workloads on this DB server; keep the environment isolated.']}


def balances(db, ids):
    result = {}
    ids = sorted(set(ids))
    for offset in range(0,len(ids),500):
        part = ids[offset:offset+500]
        query = 'SELECT id,balance FROM wallet WHERE id IN ('+','.join(['%s']*len(part))+')'
        for r in rows(db, query, [binary(i) for i in part]):
            result[text_id(r['id'])] = r['balance']
    if len(result) != len(ids):
        raise RuntimeError('Missing fixture wallets')
    db.commit()
    return result


def reconcile(db, initial, prefix, mode, expected_overspend):
    current = balances(db, initial.keys())
    changes = defaultdict(lambda:Decimal(0))
    tx = rows(db, '''SELECT from_wallet_id,to_wallet_id,amount,idempotency_key,initiated_by
      FROM game_transaction WHERE LEFT(HEX(idempotency_key),8)=%s''', (prefix.upper(),))
    seen = set()
    errors = []
    for t in tx:
        f,w = text_id(t['from_wallet_id']),text_id(t['to_wallet_id'])
        if f not in initial or w not in initial:
            errors.append('Run transaction affected an unexpected wallet')
        changes[f] -= t['amount']; changes[w] += t['amount']
        key = (t['initiated_by'],t['idempotency_key'])
        if key in seen:
            errors.append('Duplicate payment effect')
        seen.add(key)
    mismatches = [wid for wid,balance in initial.items() if current[wid] != balance+changes[wid] or current[wid]<0]
    if mismatches:
        errors.append(f'{len(mismatches)} balance mismatches/negative balances')
    expected = {'idempotency':1,'conflict':1,'security':0,'overspend':expected_overspend,
                'smoke':0,'read':0,'spike':0,'soak':0,'login':0}.get(mode)
    if expected is not None and len(tx)!=expected:
        errors.append(f'Expected {expected} commits, observed {len(tx)}')
    return {'committed_transfers':len(tx),'wallets_checked':len(initial),
            'balance_mismatches':len(mismatches),'errors':errors,'passed':not errors}


def run(args):
    if args.seconds > 600 or args.seconds < 1 or args.rate < 1 or args.vus < 1:
        raise RuntimeError('Use 1–600 seconds, a positive rate and a positive VU count')
    if args.base_url not in ('http://localhost:8080','http://127.0.0.1:8080'):
        raise RuntimeError('This runner is restricted to the local test server on port 8080')
    if args.vus>1000 or args.max_vus>10000:
        raise RuntimeError('Review generator memory before increasing beyond 1,000 contention VUs / 10,000 max VUs')
    # Connectivity check happens before creating a run or sending any mutating requests.
    try:
        urllib.request.urlopen(args.base_url+'/api/auth/me',timeout=3)
    except urllib.error.HTTPError as error:
        if error.code!=401:
            raise RuntimeError(f'Unexpected preflight response: {error.code}') from error
    except OSError as error:
        raise RuntimeError('Start BoardWallet on port 8080 before running the benchmark') from error
    fixture = json.loads((BENCH/'participants.json').read_text())
    env_values = dict(line.split('=',1) for line in (PROJECT/'.env').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    secret = base64.b64decode(env_values['JWT_SECRET'].strip().strip('\"\''),validate=True)
    props = (PROJECT/'src/main/resources/application.properties').read_text()
    if 'jwt.issuer=boardwallet' not in props or 'jwt.expiry=900' not in props:
        raise RuntimeError('JWT configuration changed; update benchmark token settings first')
    credentials = json.loads((SEED_DATA/'test-credentials.json').read_text())
    u = fixture['users'][0]
    request = urllib.request.Request(args.base_url+'/api/auth/login',
        json.dumps({'username':u['username'],'password':credentials['password']}).encode(),
        {'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=10) as response:
        actual_token = json.load(response)['accessToken']
    request = urllib.request.Request(args.base_url+'/api/auth/me', headers={'Authorization':'Bearer '+actual_token})
    with urllib.request.urlopen(request,timeout=10) as response:
        if response.read().decode()!=u['username']:
            raise RuntimeError('Authenticated identity mismatch')
    run_id = datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    out = BENCH/'runs'/run_id
    out.mkdir(parents=True, mode=0o700)
    prefix = uuid.uuid4().hex[:8]
    db = connect(PROJECT)
    if rows(db,'SELECT 1 FROM game_transaction WHERE LEFT(HEX(idempotency_key),8)=%s LIMIT 1',(prefix.upper(),)):
        raise RuntimeError('Run key prefix already exists; rerun to allocate another')
    selected = fixture['users'] if args.mode in ('mixed','read','smoke','spike','soak','login') else fixture['hot']+[fixture['outsider']]
    initial = balances(db,[w for u in selected for w in (u['wallet_id'],u['recipient_wallet_id'])])
    save(out/'balances-before.json', initial)
    amount = (initial[fixture['hot'][0]['wallet_id']]/2).quantize(Decimal('0.01'),rounding='ROUND_DOWN')
    if args.mode=='overspend' and amount<=0:
        raise RuntimeError('Overspend fixture needs a positive balance; choose a fresh funded pair')
    for user in fixture['users']+fixture['hot']+[fixture['outsider']]:
        user['token'] = jwt(user['username'],secret)
    fixture.update(prefix=prefix,password=credentials['password'],overspend_amount=str(amount),
                   expired_token=jwt(fixture['hot'][0]['username'],secret,True))
    users = fixture.pop('users')
    save(out/'users.json', users)
    save(out/'fixture.json', fixture)
    metadata = {'mode':args.mode,'requested_rps':args.rate,'requested_seconds':args.seconds,
                'vus_for_contention':args.vus,'user_pool':len(users),'base_url':args.base_url,
                'platform':platform.platform(),'cpu_logical':psutil.cpu_count(),'ram_bytes':psutil.virtual_memory().total,
                'k6':subprocess.check_output(['k6','version'],text=True).strip(),
                'authentication':'Local pre-issued HS256 tokens for API scenarios; real login preflight; login mode benchmarks BCrypt.',
                'thresholds':{'p95_ms':args.p95_ms,'p99_ms':args.p99_ms},
                'started_at':datetime.now(timezone.utc).isoformat()}
    metadata['database_settings'] = rows(db, "SHOW VARIABLES WHERE Variable_name IN ('version','innodb_buffer_pool_size','max_connections','transaction_isolation','innodb_lock_wait_timeout')")
    metadata['source_commit'] = subprocess.check_output(['git','-C',str(PROJECT),'rev-parse','HEAD'],text=True).strip()
    metadata['fixture_baseline'] = json.loads((SEED_DATA/'validation-report.json').read_text())['counts']
    save(out/'metadata.json', metadata)
    monitor = Monitor()
    before = monitor.snapshot()
    save(out/'db-before.json',before)
    env = os.environ.copy()
    env.update(MODE=args.mode,BASE_URL=args.base_url,RATE=str(args.rate),DURATION=f'{args.seconds}s',VUS=str(args.vus),
               FIXTURE=str(out/'fixture.json'),USERS=str(out/'users.json'),SUMMARY=str(out/'k6-summary.json'),
               P95_MS=str(args.p95_ms),P99_MS=str(args.p99_ms),PRE_VUS=str(args.pre_vus),MAX_VUS=str(args.max_vus))
    print(f'Running {args.mode}; artifacts: {out}',flush=True)
    started = time.monotonic()
    telemetry_errors = []
    with (out/'k6.log').open('w') as log, (out/'resource-samples.jsonl').open('w') as samples:
        process = subprocess.Popen(['k6','run','--out',f'json={out}/metrics.json.gz',str(HERE/'load.js')],env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                try:
                    samples.write(json.dumps(monitor.sample(),default=str)+'\n'); samples.flush()
                except Exception as error:
                    telemetry_errors.append(type(error).__name__+': '+str(error))
                time.sleep(1)
        finally:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=20)
    elapsed = time.monotonic()-started
    after = monitor.snapshot()
    save(out/'db-after.json',after)
    delta = database_delta(before,after)
    save(out/'database-report.json',delta)
    monitor.db.close()
    integrity = reconcile(db,initial,prefix,args.mode,int(initial[fixture['hot'][0]['wallet_id']]//amount) if amount else 0)
    save(out/'integrity-report.json',integrity)
    db.close()
    summary_path = out/'k6-summary.json'
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
    passed = process.returncode==0 and integrity['passed'] and not telemetry_errors and bool(summary)
    report = {'passed':passed,'k6_exit_code':process.returncode,'elapsed_wall_seconds':round(elapsed,2),
              'integrity':integrity,'telemetry_errors':telemetry_errors,
              'api_metrics':summary.get('metrics',{}),
              'limitations':['No injected rollback test, GC metrics or connection-pool wait metrics.',
                             'No exact per-request row-lock percentile. Database histogram percentiles are approximations.',
                             'One local machine shares load-generator, app, and database resources.']}
    save(out/'report.json',report)
    report_text = (f'# BoardWallet benchmark: {args.mode}\n\n'
        f'Overall result: {"PASS" if passed else "FAIL / inspect evidence"}\n\n'
        f'Unique committed transfers: {integrity["committed_transfers"]}. '
        f'Balance mismatches: {integrity["balance_mismatches"]}.\n\n'
        'See report.json for API metrics, database-report.json for query histogram percentiles '
        'and counter deltas, and resource-samples.jsonl for CPU/RSS and observed lock waits.\n\n'
        '| API metric | Values |\n|---|---|\n')
    for name,metric in summary.get('metrics',{}).items():
        if name.startswith('api_latency_ms') or name in ('measured_requests','unexpected_response_rate','dropped_iterations','http_req_failed'):
            report_text += f'| {name} | {json.dumps(metric.get("values",{}))} |\n'
    report_text += '\nDatabase query percentiles are approximate histogram upper bounds. '
    report_text += 'Row-lock totals and deadlocks are server-wide deltas. '
    report_text += 'No injected rollback, GC or connection-pool timing claim is made.\n'
    (out/'report.md').write_text(report_text)
    print(json.dumps({'passed':passed,'integrity':integrity,'output':str(out)},indent=2))
    return 0 if passed else 1


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('prepare','run'))
    parser.add_argument('--mode',choices=MODES,default='smoke')
    parser.add_argument('--rate',type=int,default=10)
    parser.add_argument('--seconds',type=int,default=30)
    parser.add_argument('--vus',type=int,default=50)
    parser.add_argument('--pre-vus',type=int,default=100)
    parser.add_argument('--max-vus',type=int,default=500)
    parser.add_argument('--p95-ms',type=float,default=500)
    parser.add_argument('--p99-ms',type=float,default=1500)
    parser.add_argument('--base-url',default='http://localhost:8080')
    args = parser.parse_args()
    if args.pre_vus<1 or args.max_vus<args.pre_vus:
        parser.error('Require 1 <= pre-vus <= max-vus')
    if args.action=='prepare':
        prepare(); return 0
    return run(args)


if __name__=='__main__':
    sys.exit(main())
