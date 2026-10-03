package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Organization categories, independent of a provider's manga publication status. */
object MangaroLibraryShelves {
    enum class Shelf(val storageKey: String, val title: String) {
        FAVORITE("favorite", "مفضلة"),
        READING("reading", "أتابعه حاليًا"),
        LATER("later", "مشاهدة لاحقًا"),
        COMPLETED("completed", "مكتمل"),
        PAUSED("paused", "متوقف مؤقتًا"),
    }
    data class Binding(val shelf: Shelf, val categoryId: Long)

    private val mutex = Mutex()
    private val mutableBindings = MutableStateFlow<List<Binding>>(emptyList())
    val bindings = mutableBindings.asStateFlow()

    suspend fun ensure(): List<Binding> = mutex.withLock {
        val repository = Injekt.get<CategoryRepository>()
        val preferences = Injekt.get<PreferenceStore>()
        var categories = repository.getAll().filterNot(Category::isSystemCategory)
        val result = Shelf.entries.map { shelf ->
            val preference = preferences.getLong("mangaro.library.shelf.${shelf.storageKey}", -1L)
            // Names are used only to adopt a unique existing category on first setup.
            // Afterwards a renamed category is still recognized by its persisted ID.
            val category = categories.firstOrNull { it.id == preference.get() }
                ?: categories.singleOrNull { it.name == shelf.title }
                ?: run {
                    val before = categories.map { it.id }.toSet()
                    check(Injekt.get<CreateCategoryWithName>().await(shelf.title) is CreateCategoryWithName.Result.Success)
                    categories = repository.getAll().filterNot(Category::isSystemCategory)
                    categories.single { it.id !in before && it.name == shelf.title }
                }
            preference.set(category.id)
            Binding(shelf, category.id)
        }
        mutableBindings.value = result
        result
    }
}
