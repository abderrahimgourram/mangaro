package mihon.domain.account

/** Ephemeral cosmetic feedback; only confirmed progression reads may establish/update this baseline. */
data class RankMilestone(val userId: String,val before: Int,val level: Int,val addedSlots: Int)
class RankMilestoneTracker {
    private var owner: String? = null
    private var level: Int? = null
    private var pending: RankMilestone? = null
    @Synchronized fun confirm(userId: String,serverLevel: Int) {
        if(owner!=userId || (pending?.level ?: 0)>serverLevel) pending=null
        owner=userId;level=serverLevel
    }
    @Synchronized fun updated(userId: String,serverLevel: Int,role: AccountRole) {
        val before=level.takeIf {owner==userId}
        if(owner!=userId || role!=AccountRole.USER || (pending?.level ?: 0)>serverLevel) pending=null
        if(before!=null && role==AccountRole.USER && ProfileIdentity.tier(serverLevel)>ProfileIdentity.tier(before)) {
            pending=RankMilestone(userId,before,serverLevel,ProfileIdentity.favoriteSlots(serverLevel,role)-ProfileIdentity.favoriteSlots(before,role))
        }
        owner=userId;level=serverLevel
    }
    @Synchronized fun consume(userId: String): RankMilestone? {
        if(owner!=userId) return null
        return pending.also {pending=null}
    }
    @Synchronized fun reset() {owner=null;level=null;pending=null}
}
