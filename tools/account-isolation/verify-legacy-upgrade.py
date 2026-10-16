#!/usr/bin/env python3
"""Read-only input; test additive v16->v17 on a disposable copy of a real legacy DB.
No row values, account IDs or paths from user data are printed.
"""
import argparse, hashlib, json, pathlib, sqlite3, tempfile, subprocess, sys


def state(db):
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'local_account_%' ORDER BY name")]
    result = {}
    for table in tables:
        rows = db.execute('SELECT * FROM "' + table.replace('"', '""') + '" ORDER BY rowid').fetchall()
        result[table] = {'rows': len(rows), 'sha256': hashlib.sha256(repr(rows).encode()).hexdigest()}
    return result


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('legacy_database', type=pathlib.Path); args = parser.parse_args()
    migration = pathlib.Path(__file__).resolve().parents[2] / 'data/src/main/sqldelight/tachiyomi/migrations/16.sqm'
    with tempfile.TemporaryDirectory(prefix='mangaro-upgrade-') as folder:
        original = sqlite3.connect('file:' + str(args.legacy_database.resolve()) + '?mode=ro', uri=True)
        path = pathlib.Path(folder) / 'upgrade.sqlite'; db = sqlite3.connect(path); original.backup(db); original.close()
        assert db.execute('PRAGMA user_version').fetchone()[0] == 16, 'Expected actual code25/26 schema16'
        before = state(db)
        db.executescript('BEGIN IMMEDIATE;\n' + migration.read_text() + '\nPRAGMA user_version=17;\nCOMMIT;')
        assert state(db) == before, 'Legacy rows changed during additive migration'
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
        assert db.execute('SELECT owner,guest_generation FROM local_account_context').fetchone() == ('guest', 0)
        assert db.execute('SELECT count(*) FROM local_account_snapshots').fetchone()[0] == 0
        # Exercise the real cleanup queries against history-only archived ownership.
        root = migration.parents[5]
        queries = migration.parent.parent / 'data'
        chapter_id, manga_id = db.execute('SELECT _id,manga_id FROM chapters LIMIT 1').fetchone()
        source_id = db.execute('SELECT source FROM mangas WHERE _id=?', (manga_id,)).fetchone()[0]
        db.execute('SAVEPOINT archive_retention')
        db.execute('UPDATE mangas SET favorite=0 WHERE _id=?', (manga_id,))
        db.execute("INSERT INTO local_account_snapshots VALUES('fixture-owner','history',-999,?)", (json.dumps({'chapter_id': chapter_id}),))
        chapter_sql = (queries / 'chapters.sq').read_text().split('removeChaptersWithIds:\n',1)[1].split(';',1)[0]
        db.execute(chapter_sql.replace(':chapterIds','(?)'), (chapter_id,))
        assert db.execute('SELECT count(*) FROM chapters WHERE _id=?',(chapter_id,)).fetchone()[0] == 1
        manga_sql = (queries / 'mangas.sq').read_text().split('deleteNonLibraryManga:\n',1)[1].split(';',1)[0]
        db.execute(manga_sql.replace(':sourceIds','(?)').replace(':keepReadManga','?'), (source_id,0))
        assert db.execute('SELECT count(*) FROM mangas WHERE _id=?',(manga_id,)).fetchone()[0] == 1
        db.execute('ROLLBACK TO archive_retention');db.execute('RELEASE archive_retention');db.commit()
        assert state(db) == before
        db.execute('PRAGMA journal_mode=WAL'); db.close()
        # Kill a process after SQLite has written uncommitted personal-state changes into WAL.
        child = subprocess.Popen([sys.executable, '-c', '''import sqlite3,sys,time
x=sqlite3.connect(sys.argv[1]);x.execute('BEGIN IMMEDIATE')
x.execute("UPDATE local_account_context SET transitioning=1,owner='uncommitted-audit'")
x.execute("UPDATE mangas SET favorite=0,notes='' ")
x.execute("DELETE FROM history")
print('READY',flush=True);time.sleep(60)
''', str(path)], stdout=subprocess.PIPE, text=True)
        try:
            assert child.stdout.readline().strip() == 'READY'; child.kill(); child.wait(timeout=5)
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=5)
        db = sqlite3.connect(path)
        assert state(db) == before, 'Killed SQLite transaction changed legacy personal rows'
        assert db.execute('SELECT owner,transitioning FROM local_account_context').fetchone() == ('guest', 0)
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        db.close()
        print(json.dumps({'passed': True, 'sourceSchema': 16, 'targetSchema': 17, 'unchangedLegacyTables': len(before), 'preservedRows': {k: v['rows'] for k, v in before.items()}, 'killedWalTransactionRolledBack': True, 'historyOnlyArchiveRetained': True}))


if __name__ == '__main__': main()
