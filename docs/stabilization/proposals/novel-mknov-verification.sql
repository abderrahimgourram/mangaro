-- READ ONLY. Target pehsxthjetlltlsqcbfa. Catalog/constant expressions only.
select current_database(),current_user,version(),current_setting('transaction_read_only');
select c.conrelid::regclass::text,c.conname,pg_get_constraintdef(c.oid),c.convalidated
from pg_constraint c where c.conrelid in ('public.cloud_novel_state'::regclass,'public.cloud_novel_progress'::regclass)
order by c.conrelid::regclass::text,c.conname;
select n.nspname,p.proname,pg_get_functiondef(p.oid),p.proacl,p.prosecdef
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname='mangaro_private' and p.proname in ('cloud_novel_url','cloud_novel_position','cloud_stamp');
select schemaname,tablename,policyname,roles,cmd,qual,with_check from pg_policies
where schemaname='public' and tablename in ('cloud_novel_state','cloud_novel_progress');
select source,url,mangaro_private.cloud_novel_url(source,url) accepted
from (values
 ('novel.kolnovel','https://kolnovel.com/novel/existing'),
 ('novel.cenele','https://cenele.com/novel/existing'),
 ('novel.sunovels','https://sunovels.com/novel/existing'),
 ('novel.seanovel','https://seanovel.org/novels/existing'),
 ('novel.mknov','https://mknov.com/novel/96'),
 ('novel.mknov','https://mknov.com/novel/96/chapter/20716'),
 ('novel.mknov','https://mknov.com.evil.org/novel/96'),
 ('novel.mknov','https://api.mknov.com/novel/96')
) cases(source,url);
-- Expected before: four old providers true, all Mknov false.
-- Expected after: old four + exact Mknov work/chapter true; both hostile/unsupported hosts false.
-- No row-count queries, user records, writes, or production lock tests here.
