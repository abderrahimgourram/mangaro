-- Run read-only AFTER the additive schema. No title/fuzzy candidate merging and no raw user data.
begin read only;
select (select count(*) from public.community_comments) comments,
 (select count(*) from public.community_comments where parent_comment_id is not null) replies,
 (select count(*) from public.community_ratings) historical_ratings,
 (select count(*) from public.community_editions where revoked_at is null) verified_editions;
select e.work_key,count(distinct e.edition_key) editions,
 count(distinct c.id) historical_comments
 from public.community_editions e left join public.community_comments c
 on c.target_type='manga' and c.manga_key=e.edition_key
 where e.revoked_at is null group by e.work_key;
select count(*) duplicate_user_work_groups from (
 select r.user_id,coalesce(e.work_key,r.manga_key) from public.community_ratings r
 left join public.community_editions e on e.edition_key=r.manga_key and e.revoked_at is null
 where r.target_type='manga' group by r.user_id,coalesce(e.work_key,r.manga_key) having count(*)>1
) duplicates;
select count(*) orphan_replies from public.community_comments c left join public.community_comments p on p.id=c.parent_comment_id
 where c.parent_comment_id is not null and p.id is null;
commit;
