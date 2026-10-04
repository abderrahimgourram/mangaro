begin;
-- Extend the existing curated showcase; preserve privacy, order and deployed slot caps.
alter table public.public_favorites add column featured boolean not null default false;
create or replace function mangaro_private.save_public_showcase(p_enabled boolean,p_favorites jsonb) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=auth.uid(); cap integer; item jsonb; n integer:=0;
begin
 if u is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_enabled is null or p_favorites is null or jsonb_typeof(p_favorites)<>'array' or jsonb_array_length(p_favorites)>20 then raise exception 'Invalid showcase' using errcode='22023'; end if;
 insert into public.public_showcase_settings(user_id) values(u) on conflict do nothing;
 perform 1 from public.public_showcase_settings where user_id=u for update;
 select case when r.role='developer' or p.level>=25 then 20 when p.level>=15 then 15 when p.level>=5 then 10 else 5 end into cap
 from public.user_progression p left join public.profile_roles r on r.user_id=p.user_id where p.user_id=u;
 if cap is null or jsonb_array_length(p_favorites)>cap then raise exception 'Showcase limit reached' using errcode='22023'; end if;
 if (select count(*) from jsonb_array_elements(p_favorites) f where f->>'featured'='true')>3 then raise exception 'Featured limit reached' using errcode='22023'; end if;
 delete from public.public_favorites where user_id=u;
 for item in select * from jsonb_array_elements(p_favorites) loop
  if jsonb_typeof(item)<>'object' or exists(select 1 from jsonb_object_keys(item) k where k not in ('manga_key','title','cover_path','featured')) then raise exception 'Private metadata forbidden' using errcode='22023'; end if;
  if item ? 'featured' and jsonb_typeof(item->'featured')<>'boolean' then raise exception 'Invalid featured flag' using errcode='22023'; end if;
  insert into public.public_favorites(user_id,manga_key,title,cover_path,sort_order,featured)
  values(u,item->>'manga_key',btrim(item->>'title'),item->>'cover_path',n,coalesce((item->>'featured')::boolean,false)); n:=n+1;
 end loop;
 update public.public_showcase_settings set enabled=p_enabled where user_id=u;
end $$;
revoke all on function mangaro_private.save_public_showcase(boolean,jsonb) from public,anon;
grant execute on function mangaro_private.save_public_showcase(boolean,jsonb) to authenticated;
create or replace function public.community_public_profile(p_user_id uuid) returns jsonb language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('user_id',p.user_id,'display_name',p.display_name,'username',p.username,
 'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'cover_path',p.cover_path,'bio',p.bio,'updated_at',p.updated_at,
 'role',coalesce(r.role,'user'),'level',coalesce(l.level,1),
 'comment_count',(select count(*) from public.community_comments where user_id=p.user_id),
 'rating_count',(select count(*) from public.community_ratings where user_id=p.user_id),
 'chapters_read',mangaro_private.public_read_count(p.user_id),
 'showcase_enabled',exists(select 1 from public.public_showcase_settings s where s.user_id=p.user_id and s.enabled),
 'favorites',coalesce((select jsonb_agg(jsonb_build_object('manga_key',f.manga_key,'title',f.title,'cover_path',f.cover_path,'featured',f.featured) order by f.sort_order)
 from public.public_favorites f where f.user_id=p.user_id and exists(select 1 from public.public_showcase_settings s where s.user_id=p.user_id and s.enabled)),'[]'::jsonb))
 from public.profiles p left join public.profile_roles r on r.user_id=p.user_id
 left join public.community_author_levels(array[p_user_id]) l on l.user_id=p.user_id where p.user_id=p_user_id;
$$;
commit;
