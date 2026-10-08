package tachiyomi.data.category

import mihon.domain.account.LocalCloudChanges

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries
            .getCategory(id, ::mapCategory)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoriesQueries
            .getCategories(::mapCategory)
            .awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries
            .getCategories(::mapCategory)
            .subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, ::mapCategory)
            .awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, ::mapCategory)
            .subscribeToList()
    }

    override suspend fun insert(category: Category) {
        val sigilOwner = mihon.domain.sigils.SigilEvents.captureOwner?.invoke() ?: "_no_owner"
        val categoryId = database.categoriesQueries.insertReturningId(
            name = category.name,
            order = category.order,
            flags = category.flags,
        ).awaitAsOne()
        LocalCloudChanges.changed(LocalCloudChanges.Kind.COLLECTIONS)
        mihon.domain.sigils.SigilEvents.emit(mihon.domain.sigils.SigilEvents.Kind.CATEGORIES, categoryId, owner=sigilOwner)
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        database.categoriesQueries.updateName(name = name, categoryId = categoryId)
        LocalCloudChanges.changed(LocalCloudChanges.Kind.COLLECTIONS)
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        database.categoriesQueries.updateFlags(flags = flags, categoryId = categoryId)
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoriesQueries.updateAllFlags(flags = flags)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        database.transaction {
            orderedIds.forEachIndexed { index, categoryId ->
                database.categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
            }
        }
        LocalCloudChanges.changed(LocalCloudChanges.Kind.COLLECTIONS)
    }

    override suspend fun delete(categoryId: Long) {
        database.categoriesQueries.delete(categoryId = categoryId)
        LocalCloudChanges.changed(LocalCloudChanges.Kind.COLLECTION_DELETED, categoryId)
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
        )
    }
}
