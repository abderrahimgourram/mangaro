# Mangaro encrypted backup and recovery tools

Target: `pehsxthjetlltlsqcbfa`, existing private R2 bucket
`mangaro-secure-backups`. These tools never deploy a database migration, create
a bucket, publish an APK, schedule a job or delete existing backup objects.

## Security boundary and current limitations

Never reuse `mangaro-backup-writer`. The owner confirmed revocation on
2026-10-21; independent API verification was unavailable. Cloudflare connector
whoami/search/docs returned internal error -32603. Existing Wrangler OAuth was
successfully refreshed without a new login. Its token-metadata requests still
returned403/9109 and audit logs403/10000. It verified the actual Standard bucket,
disabled r2.dev and absence of custom domains. An owner confirmation is not an API revocation receipt or an audit-log
review. No token secret was read, reproduced or used.

Use `mangaro-backup-writer-v2`: R2 **Object Read & Write**, scoped only to this
bucket. Account-wide Admin Read & Write is unsuitable. Account API bearer tokens
and the S3 Access Key ID/Secret Access Key are different credentials. Inspect the
actual token policy in the authorized dashboard; a local credential name alone
does not prove least privilege. Keep r2.dev and custom-domain public access off.
An unsigned S3 GET test alone cannot prove every public endpoint is disabled.

`load_config` rejects unreviewed revocation/privacy/scope, unrelated projects,
unexpected endpoints, old credential names, excessive budgets and missing
recovery/coverage review. Review flags are deliberate operator attestations,
**not automatic discovery of remote security policy**. Never set them just to
bypass an error. Nothing runs on a schedule; production export is explicit.

## Local installation and key recovery

```bash
python3 tools/backups/install_tools.py --cache "$HOME/.cache/mangaro-backup-tools"
```

Pins age1.3.2 and rclone1.75.2 by SHA-256 from official release evidence. Does not
install a system package or alter the Android dependency stack. Existing
PostgreSQL17.11 tools can be reused; do not substitute an unreviewed toolchain.

The actual owner recovery identity is outside Git:
`~/.local/share/mangaro-backup/recovery/identity.txt`, directory0700/file0600.
The public recipient is in `recipient.txt`. Never print/cat the private identity,
send it to chat, put it in CI, or upload it unencrypted to R2. Creating a local
identity is **not** proof of redundant recovery. The owner must independently
retain a protected offline copy, preferably on an encrypted removable filesystem.

`verify_recovery_copy.py` requires a different filesystem and tests decryption
of a fresh small synthetic challenge. It does not copy the key automatically.
For example, after the owner has created the protected offline copy:

```bash
python3 tools/backups/verify_recovery_copy.py \
  --age "$HOME/.cache/mangaro-backup-tools/bin/age" \
  --original "$HOME/.local/share/mangaro-backup/recovery/identity.txt" \
  --copy /OWNER/PRIVATE/OFFLINE/identity.txt \
  --result "$HOME/.local/share/mangaro-backup/recovery/copy-verification.json"
```

Do not label redundancy verified from a second file on the same laptop disk.
Current operational state: **RECOVERY KEY REDUNDANCY NOT VERIFIED**.

## Owner-terminal credential setup

R2 dashboard → Overview → Account Details → API Tokens → Manage → create new
dedicated token, Object Read & Write, apply only to `mangaro-secure-backups`.
Never paste values into chat or place them in command arguments/history.

```bash
python3 tools/backups/configure_local.py
```

This requires an actual local terminal, masks both S3 credential fields and
stores them using Secret Service `secret-tool` via stdin. There is no plaintext
credential-file fallback. Nonsecret account/endpoint/recipient settings are
saved0600 outside Git at `~/.config/mangaro-backup/r2.json`. It refuses to overwrite
an existing configuration. Policy-review flags deliberately start false.

There is no rclone.conf: only the required named remote is injected into child
environment variables; inherited AWS/RCLONE/PG destinations are stripped. Local
same-user/root process inspection remains an OS trust boundary. Rclone obscure
is not encryption and is not used to protect secrets here.

## Real R2 probe (requires actual reviewed new credentials)

`r2_probe.py` lists only the intended bucket, uploads a unique small encrypted
synthetic object, reads its ciphertext back, verifies SHA-256/size, decrypts it,
checks the actual payload and rejects an unsigned S3 GET. Only after successful
verification does it delete the exact disposable object it created. It cannot
delete historical backups or configure/create/delete buckets.

No real R2 result is claimed when credentials are absent. The local S3 drill
below must never be relabeled a Cloudflare R2 connectivity test. Read/write on
one bucket does not itself prove denied access elsewhere or lack of admin
permissions; those require authorized policy metadata/appropriate harmless
probes, not attempts to create/delete unrelated buckets.

## Streaming design

Each component uses `pg_dumpall --roles-only --no-role-passwords` or a full
plain `pg_dump` → `gzip -1` → `age -r <public recipient>` → bounded hash bridge →
`rclone rcat`. No plaintext or compressed SQL file is written locally. Each
process exit matters; exporter failure is fatal even if compression/encryption
and upload finish. Stderr is drained without persisting potentially private SQL
errors. All process groups are killed on timeout/error. Failed/partial objects
remain **staging only**, with no successful manifest. The production command
first writes a durable `.attempt.json` journal containing planned object keys.
Do not interpret their
existence as a backup; identify a failed attempt before any deliberate cleanup.

Rclone: streaming cutoff1MiB, S3 chunk8MiB, one upload part at a time, one transfer,
buffer-size0, no whole-stream retries, bounded connection/I/O timeouts. Multipart
buffers consume RAM (roughly concurrency × chunk size plus cutoff/process/SDK
overhead); they are not free or a hard total-memory guarantee. Unknown-size
streams have a finite part limit. The configured total ciphertext budget is
enforced across components, at most10GiB and default1GiB; aborts do not guarantee
zero API/storage charges. Production restore still needs adequate independent
capacity. Do not use RAM tmpfs as a durable recovery copy.

Every attempt gets a UTC+random UUID prefix, never an existing historical key.
Existence checks fail closed except the documented rclone not-found codes or
its S3 virtual-directory sentinel (IsDir=true/Size=-1 after an exact object miss). Rcat
itself overwrites: UUIDs and checks avoid accidental reuse; this is not an atomic
If-None-Match/Object Lock guarantee against a concurrent malicious writer.
Object Read & Write can delete its bucket's backups, so credential rotation,
independent key custody and a separate recovery copy remain necessary.

After upload, an independent complete ciphertext read-back must match digest
and size. A0600 local manifest and encrypted offsite receipt preserve component
keys, sizes, digests, timestamp, project, recipient, source/tool versions and
scope limitations. Receipt status is **UPLOADED_NOT_RESTORED**, never VALID.
A successful later restore needs separate retained verification evidence.
The local manifest is an integrity reference, not a cryptographic signature or
proof of trusted origin against an attacker able to replace all copies.

## Production export boundary

`streaming.py --help` lists explicit paths. Requires an already-reviewed
configuration, actual independent key-copy verification, actual coverage review,
and locally protected `~/.pg_service.conf`/`~/.pgpass`. No passwords in argv.
Service `mangaro-production-backup` must use the exact project direct host/user,
or the **actual dashboard-provided** session-pooler host with
`postgres.pehsxthjetlltlsqcbfa`, port5432, database postgres and verify-full TLS.
No invented region, transaction pooler, TLS downgrade or password reset. A
read-only preflight checks database/version/role/session; pg_dump must fail
rather than silently fall back to an owner-only RLS subset. No production
restore CLI or remote DDL operation is provided.

A full PostgreSQL dump's selection differs from Supabase CLI defaults. Audit
actual coverage before export: public/custom/mangaro_private schemas, Auth,
novel progress, other user tables, migration history, effective grants, roles,
FK/unique/CHECK constraints, sequences, triggers/functions/RLS and managed
extension members. Password-free role exports do not preserve LOGIN passwords.
The DB dump has its own coherent snapshot; the separate global role export is
not an atomic cross-catalog snapshot. Avoid DDL/role changes during the window.
Use the same exported snapshot for authoritative row-count comparison if needed;
live counts during concurrent writes are not an equality proof.

Supabase-managed extensions/platform objects may require a matching isolated
Supabase-compatible environment and permissions. Never ignore restore errors,
strip policies/grants to obtain a green result, or call a partial dump complete.
Storage **object bytes**, external Auth/provider settings, platform secrets and
encryption-root-key dependencies are outside a PostgreSQL logical dump. Their
recovery requires a separate reviewed procedure.

## Actual automated local acceptance

```bash
python3 -W error::ResourceWarning -m unittest discover -s tools/backups -p 'test_*.py' -v
python3 tools/backups/drill.py --root /NEW/PRIVATE/DIRECTORY \
  --pg-bin /EXISTING/POSTGRES17/bin --bin /PINNED/TOOLS/bin \
  --identity /PRIVATE/RECOVERY/identity.txt
```

The drill refuses reused directories, needs at least1GiB free, binds PostgreSQL
only to private Unix sockets, serves synthetic S3 only on127.0.0.1 with random
in-memory SigV4 credentials, and stops both clusters/server in finally. It reuses
Mangaro's exact existing fixture/migrations and snapshot comparer, with A/B
accounts, old providers/Mknov, bookmarks, read state, revisions, tombstones and
migration history. No production URL or credential is used. New SQL applies
only to the disposable fixture, never production.

Tests real streaming at1KiB/1MiB/17MiB (multipart), complete remote read-back,
decryption, second-cluster fail-on-error restore, schema/data/permissions/RLS/
sequence/function equality, account isolation, wrong identity, bad digest,
partial exporter failure and overwrite rejection. The CI workflow performs this
same real local drill and uploads only sanitized aggregate result.json, not
identities, encrypted objects, logs, dumps or PostgreSQL data directories.

## Cost and operational limits

Owner-reported initial R2 bill$0.00 is not a current billing guarantee. Standard
currently includes10GB-month/1million ClassA/10million ClassB monthly usage;
storage/operations above allowance are billable, shared with other account use.
R2 egress is currently free, but full read-back/restore still uses read operations
and Supabase export may have its own egress/account limits. No billing limit,
lifecycle, retention deletion or paid service is enabled by these scripts.

## Sources reviewed 2026-10-21

- https://developers.cloudflare.com/r2/api/tokens/
- https://developers.cloudflare.com/r2/pricing/
- https://rclone.org/commands/rclone_rcat/
- https://rclone.org/s3/
- https://github.com/FiloSottile/age
- https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore

Mknov SQL remains a separate, NOT DEPLOYED proposal, checksum
`c3c437ac77a74eb36ac4ae396b292585dd42a8f2a07af02f89e94cae60dcb660`.
No completion-v2 deployment or historical backfill is authorized by backup work.
