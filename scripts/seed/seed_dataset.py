#!/usr/bin/env python3
"""Local benchmark fixture. Never clears data; requires empty tables or --resume.

Install scripts/seed-requirements.txt into a dedicated venv. Run with --output
outside the repository. Credentials come from the project's .env; generated
test credentials are stored only in the private output directory.
"""
import argparse
import hashlib
import json
import os
import random
import re
import secrets
import time
import unicodedata
import uuid
from datetime import datetime, timezone
from pathlib import Path

import bcrypt
import pymysql
from faker import Faker

TABLES = ('user', 'board', 'wallet', 'game_transaction', 'refresh_tokens')
SEED = 20261116
USER_SQL = 'INSERT INTO user (id,username,password,is_enabled) VALUES (%s,%s,%s,%s)'
BOARD_SQL = '''INSERT INTO board
 (id,name,join_code,created_by,starting_amount,status,created_at,updated_at,started_at)
 VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)'''
WALLET_SQL = '''INSERT INTO wallet
 (id,board_id,user_id,wallet_type,balance,created_at,updated_at)
 VALUES (%s,%s,%s,%s,%s,%s,%s)'''
TX_SQL = '''INSERT INTO game_transaction
 (id,board_id,from_wallet_id,to_wallet_id,amount,reason,initiated_by,created_at)
 VALUES (%s,%s,%s,%s,%s,%s,%s,%s)'''


def uid(kind, number):
    raw = hashlib.blake2b(f'{SEED}:{kind}:{number}'.encode(), digest_size=16).digest()
    return uuid.UUID(bytes=raw, version=4).bytes


def save(path, obj):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(obj, indent=2, default=str) + '\n')
    os.chmod(temp, 0o600)
    temp.replace(path)


def connect(project):
    props = (project / 'src/main/resources/application.properties').read_text()
    if 'spring.datasource.url=jdbc:mysql://localhost:3306/boardwallet' not in props:
        raise RuntimeError('Unexpected database target; inspect configuration first')
    env = {}
    for line in (project / '.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('\"\'')
    return pymysql.connect(host='127.0.0.1', port=3306, database='boardwallet',
                           user=env['DB_USERNAME'], password=env['DB_PASSWORD'],
                           charset='utf8mb4', autocommit=False,
                           connect_timeout=10, read_timeout=180, write_timeout=180)


def counts(cur):
    result = {}
    for table in TABLES:
        cur.execute(f'SELECT COUNT(*) FROM `{table}`')
        result[table] = cur.fetchone()[0]
    return result


def insert(cur, sql, rows):
    for start in range(0, len(rows), 1000):
        cur.executemany(sql, rows[start:start + 1000])


def validate(db, users, boards):
    checks = {}
    def zero(name, sql):
        with db.cursor() as cur:
            cur.execute(sql)
            value = cur.fetchone()[0]
        checks[name] = value
        print(f'Validation {name}: {value}', flush=True)
        if value:
            raise RuntimeError(f'Validation failed: {name}={value}')
    with db.cursor() as cur:
        actual = counts(cur)
        expected = dict(user=users, board=boards, wallet=boards * 23,
                        game_transaction=boards * 22, refresh_tokens=0)
        if actual != expected:
            raise RuntimeError(f'Count mismatch: {actual} != {expected}')
        cur.execute('''SELECT players,COUNT(*) FROM
          (SELECT board_id,COUNT(*) players FROM wallet WHERE wallet_type='PLAYER'
           GROUP BY board_id) t GROUP BY players ORDER BY players''')
        distribution = dict(cur.fetchall())
        if distribution != {10: boards * 6 // 10, 20: boards * 3 // 10, 100: boards // 10}:
            raise RuntimeError(f'Unexpected board sizes: {distribution}')
    zero('users_without_membership', '''SELECT COUNT(*) FROM user u WHERE NOT EXISTS
      (SELECT 1 FROM wallet w WHERE w.user_id=u.id AND w.wallet_type='PLAYER')''')
    zero('duplicate_memberships', '''SELECT COUNT(*) FROM
      (SELECT board_id,user_id FROM wallet WHERE wallet_type='PLAYER'
       GROUP BY board_id,user_id HAVING COUNT(*)<>1) d''')
    zero('invalid_bank_count', '''SELECT COUNT(*) FROM board b WHERE
      (SELECT COUNT(*) FROM wallet w WHERE w.board_id=b.id AND w.wallet_type='BANK')<>1''')
    zero('missing_creator_membership', '''SELECT COUNT(*) FROM board b WHERE NOT EXISTS
      (SELECT 1 FROM wallet w WHERE w.board_id=b.id AND w.user_id=b.created_by
       AND w.wallet_type='PLAYER')''')
    zero('invalid_wallet_state', '''SELECT COUNT(*) FROM wallet WHERE
      (wallet_type='BANK' AND (user_id IS NOT NULL OR balance IS NOT NULL)) OR
      (wallet_type='PLAYER' AND (user_id IS NULL OR balance IS NULL OR balance<0))''')
    zero('invalid_board_state', '''SELECT COUNT(*) FROM board WHERE status<>'ACTIVE'
      OR started_at IS NULL OR finished_at IS NOT NULL OR starting_amount<>10000
      OR created_at>started_at OR started_at>updated_at''')
    zero('invalid_transaction_relationships', '''SELECT COUNT(*) FROM game_transaction t
      LEFT JOIN wallet f ON f.id=t.from_wallet_id LEFT JOIN wallet w ON w.id=t.to_wallet_id
      LEFT JOIN board b ON b.id=t.board_id LEFT JOIN user u ON u.id=t.initiated_by
      WHERE f.id IS NULL OR w.id IS NULL OR b.id IS NULL OR u.id IS NULL
      OR f.board_id<>t.board_id OR w.board_id<>t.board_id OR f.wallet_type<>'BANK'
      OR w.wallet_type<>'PLAYER' OR t.initiated_by<>b.created_by
      OR t.reason<>'STARTING_MONEY' OR t.amount<>b.starting_amount
      OR t.idempotency_key IS NOT NULL OR t.request_hash IS NOT NULL''')
    zero('balance_or_starting_money_mismatch', '''SELECT COUNT(*) FROM wallet w
      LEFT JOIN (SELECT to_wallet_id,COUNT(*) n,SUM(amount) total FROM game_transaction
                 GROUP BY to_wallet_id) t ON t.to_wallet_id=w.id
      WHERE w.wallet_type='PLAYER' AND (t.n IS NULL OR t.n<>1 OR t.total<>w.balance)''')
    zero('orphan_wallets', '''SELECT COUNT(*) FROM wallet w LEFT JOIN board b ON b.id=w.board_id
      LEFT JOIN user u ON u.id=w.user_id WHERE b.id IS NULL
      OR (w.wallet_type='PLAYER' AND u.id IS NULL)''')
    zero('invalid_users', 'SELECT COUNT(*) FROM user WHERE is_enabled<>1 OR username IS NULL')
    db.commit()
    return {'counts': actual, 'board_size_distribution': distribution, 'violations': checks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--users', type=int, default=1000000)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.users < 100 or args.users % 100:
        parser.error('--users must be a positive multiple of 100')
    started = time.monotonic()
    project = Path(__file__).resolve().parents[1]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(out, 0o700)
    db = connect(project)
    boards = args.users // 10
    state_path = out / 'checkpoint.json'
    if args.resume or args.verify_only:
        state = json.loads(state_path.read_text())
        if state['users_target'] != args.users or state['seed'] != SEED:
            raise RuntimeError('Checkpoint configuration mismatch')
    else:
        if state_path.exists():
            raise RuntimeError('Output already exists; use --resume')
        with db.cursor() as cur:
            cur.execute('SHOW TABLES')
            if set(r[0] for r in cur.fetchall()) != set(TABLES):
                raise RuntimeError('Unexpected schema tables')
            if any(counts(cur).values()):
                raise RuntimeError('Database must be empty; this script never deletes data')
        password = 'SeedOnly-' + secrets.token_urlsafe(18)
        state = {'seed': SEED, 'users_target': args.users, 'users_done': 0,
                 'boards_done': 0, 'memberships_done': 0,
                 'timestamp': datetime.now(timezone.utc).replace(tzinfo=None).isoformat(),
                 'password_hash': bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()}
        save(out / 'test-credentials.json', {'username_source': 'active-users.jsonl',
                                             'password': password,
                                             'note': 'Shared test-only password/hash; not a production account fixture'})
        save(state_path, state)
    with db.cursor() as cur:
        actual = counts(cur)
        expected = dict(user=state['users_done'], board=state['boards_done'],
                        wallet=state['memberships_done'] + state['boards_done'],
                        game_transaction=state['memberships_done'], refresh_tokens=0)
        if actual != expected:
            raise RuntimeError(f'Checkpoint/database mismatch; inspect before resume: {actual} != {expected}')
    if not args.verify_only:
        fake = Faker('en_IN')
        fake.seed_instance(SEED)
        rows = []
        for n in range(args.users):
            name = unicodedata.normalize('NFKD', fake.first_name() + '_' + fake.last_name())
            name = re.sub(r'[^a-z0-9_]', '', name.encode('ascii', 'ignore').decode().lower())
            if n < state['users_done']:
                continue
            rows.append((uid('user', n), f'{name[:80]}_{n+1:07d}', state['password_hash'], 1))
            if len(rows) == 10000 or n == args.users - 1:
                with db.cursor() as cur:
                    insert(cur, USER_SQL, rows)
                db.commit()
                state['users_done'] = n + 1
                save(state_path, state)
                rows.clear()
                print(f'Users {n+1:,}/{args.users:,}', flush=True)
        rng = random.Random(SEED)
        population = list(range(args.users))
        rng.shuffle(population)
        sizes = [10] * (boards*6//10) + [20] * (boards*3//10) + [100] * (boards//10)
        rng.shuffle(sizes)
        cursor = 0
        br, wr, tr = [], [], []
        now = datetime.fromisoformat(state['timestamp'])
        for b, size in enumerate(sizes):
            members = []
            while len(members) < size:
                if cursor < args.users:
                    candidate = population[cursor]
                    cursor += 1
                else:
                    candidate = rng.randrange(args.users)
                if candidate not in members:
                    members.append(candidate)
            if b < state['boards_done']:
                continue
            bid, bank, owner = uid('board', b), uid('bank', b), uid('user', members[0])
            br.append((bid, f'Family Traders {b+1:06d}', f'S{b+1:07d}', owner,
                       '10000.00', 'ACTIVE', now, now, now))
            wr.append((bank, bid, None, 'BANK', None, now, now))
            for member in members:
                wid = uid('wallet', f'{b}:{member}')
                wr.append((wid, bid, uid('user', member), 'PLAYER', '10000.00', now, now))
                tr.append((uid('starting', f'{b}:{member}'), bid, bank, wid,
                           '10000.00', 'STARTING_MONEY', owner, now))
            if len(br) == 100 or b == boards-1:
                with db.cursor() as cur:
                    insert(cur, BOARD_SQL, br)
                    insert(cur, WALLET_SQL, wr)
                    insert(cur, TX_SQL, tr)
                db.commit()
                state['boards_done'] = b+1
                state['memberships_done'] += len(tr)
                save(state_path, state)
                br.clear(); wr.clear(); tr.clear()
                if (b+1) % 1000 == 0 or b == boards-1:
                    print(f'Boards {b+1:,}/{boards:,}; memberships {state["memberships_done"]:,}', flush=True)
    result = validate(db, args.users, boards)
    with db.cursor() as cur:
        cur.execute('SELECT VERSION(),@@hostname,@@port')
        result['database'] = cur.fetchone()
        cur.execute('''SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
          FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
          GROUP BY TABLE_NAME,INDEX_NAME''')
        result['indexes'] = cur.fetchall()
        cur.execute('''SELECT u.id,u.username,u.password,w.id,w.board_id FROM user u
          JOIN wallet w ON w.user_id=u.id WHERE w.wallet_type='PLAYER'
          AND w.board_id=(SELECT MIN(w2.board_id) FROM wallet w2 WHERE w2.user_id=u.id)
          ORDER BY u.id LIMIT 10000''')
        samples = cur.fetchall()
    credentials = json.loads((out / 'test-credentials.json').read_text())
    assert all(bcrypt.checkpw(credentials['password'].encode(), row[2].encode()) for row in samples[:5])
    manifest = out / 'active-users.jsonl'
    with manifest.open('w') as f:
        for user, name, _, wallet, board in samples:
            f.write(json.dumps({'user_id': str(uuid.UUID(bytes=user)), 'username': name,
                                'wallet_id': str(uuid.UUID(bytes=wallet)),
                                'board_id': str(uuid.UUID(bytes=board))}) + '\n')
    os.chmod(manifest, 0o600)
    result.update(seed=SEED, faker_locale='en_IN', active_manifest_users=len(samples),
                  password_samples_verified=min(5,len(samples)),
                  elapsed_seconds=round(time.monotonic()-started, 2),
                  completed_at=datetime.now(timezone.utc).isoformat(),
                  scope='Database seed validation only; no API or load test performed')
    save(out / 'validation-report.json', result)
    db.close()
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
