-- Public aggregate of already-public manga ratings; no individual rater/private data.
create function public.community_weekly_ranking() returns jsonb
language sql stable security invoker set search_path = '' as $$
 with works as (
   select manga_key,avg(rating)::double precision average,count(*) rating_count
   from public.community_ratings where target_type='manga' group by manga_key
 ), parameters as (
   select coalesce((select avg(rating)::double precision from public.community_ratings where target_type='manga'),3.0) mean,
          greatest(2.0,ceil(coalesce(percentile_cont(0.5) within group(order by rating_count),2.0))) confidence
   from works
 ), scored as (
   select w.*, (rating_count*average+p.confidence*p.mean)/(rating_count+p.confidence) score
   from works w cross join parameters p where rating_count>=2
 ), ranked as (
   select * from scored order by score desc,rating_count desc,average desc,manga_key limit 100
 )
 select jsonb_build_object('confidence',(select confidence from parameters),'mean',(select mean from parameters),
   'works',coalesce((select jsonb_agg(jsonb_build_object('manga_key',manga_key,'average',average,'count',rating_count,'score',score)
     order by score desc,rating_count desc,average desc,manga_key) from ranked),'[]'::jsonb));
$$;
revoke all on function public.community_weekly_ranking() from public,anon,authenticated;
grant execute on function public.community_weekly_ranking() to anon,authenticated;
