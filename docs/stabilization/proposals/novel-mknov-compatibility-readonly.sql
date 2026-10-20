-- Synthetic constants only; no DDL, user records or stored function writes.
with samples(source,value,expected) as (values
('novel.kolnovel','https://kolnovel.com/novel/example/',true),
('novel.cenele','https://cenele.com/novel/example/',true),
('novel.sunovels','https://sunovels.com/novel/example/',true),
('novel.seanovel','https://seanovel.org/novels/example',true),
('novel.mknov','https://mknov.com/novel/96',true),
('novel.mknov','https://mknov.com/novel/96/chapter/20716',true),
('novel.mknov','https://mknov.com:443/novel/96',true),
('novel.mknov','https://mknov.com:444/novel/96',false),
('novel.mknov','http://mknov.com/novel/96',false),
('novel.mknov','https://mknov.com.evil.org/novel/96',false),
('novel.mknov','https://mknov.com@evil.org/novel/96',false),
('novel.mknov','https://cenele.com/novel/96',false),
('novel.unknown','https://mknov.com/novel/96',false),
('novel.mknov','',false),
('novel.mknov',NULL,false)
), evaluated as (select source,value,expected,(coalesce(char_length(value) between 1 and 4096 and value ~ (
 '^https://([a-zA-Z0-9-]+\.)*' || case source
 when 'novel.kolnovel' then 'kolnovel\.com'
 when 'novel.cenele' then 'cenele\.com'
 when 'novel.sunovels' then 'sunovels\.com'
 when 'novel.seanovel' then 'seanovel\.org'
 when 'novel.mknov' then 'mknov\.com'
 else '(?!)' end || '(:443)?(/|$)'),false)) as actual from samples) select count(*) as cases, count(*) filter(where actual=expected) as passed, bool_and(actual=expected) as all_pass from evaluated;
