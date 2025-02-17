package org.koitharu.kotatsu.filter.ui

import android.view.View
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.sortedByOrdinal
import org.koitharu.kotatsu.filter.ui.model.FilterHeaderModel
import org.koitharu.kotatsu.filter.ui.model.FilterItem
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.SuspendLazy
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment
import org.koitharu.kotatsu.search.domain.MangaSearchRepository
import java.text.Collator
import java.util.LinkedList
import java.util.Locale
import java.util.TreeSet
import javax.inject.Inject

@ViewModelScoped
class FilterCoordinator @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	dataRepository: MangaDataRepository,
	private val searchRepository: MangaSearchRepository,
	lifecycle: ViewModelLifecycle,
) : MangaFilter {

	private val coroutineScope = lifecycle.lifecycleScope
	private val repository = mangaRepositoryFactory.create(savedStateHandle.require(RemoteListFragment.ARG_SOURCE))
	private val currentState =
		MutableStateFlow(MangaListFilter.Advanced(repository.defaultSortOrder, emptySet(), null, emptySet()))
	private var searchQuery = MutableStateFlow("")
	private val localTags = SuspendLazy {
		dataRepository.findTags(repository.source)
	}
	private var availableTagsDeferred = loadTagsAsync()

	override val filterItems: StateFlow<List<ListModel>> = getItemsFlow()
		.stateIn(coroutineScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

	override val header: StateFlow<FilterHeaderModel> = getHeaderFlow().stateIn(
		scope = coroutineScope + Dispatchers.Default,
		started = SharingStarted.Lazily,
		initialValue = FilterHeaderModel(
			chips = emptyList(),
			sortOrder = repository.defaultSortOrder,
			hasSelectedTags = false,
			allowMultipleTags = repository.isMultipleTagsSupported,
		),
	)

	init {
		observeState()
	}

	override fun applyFilter(tags: Set<MangaTag>) {
		setTags(tags)
	}

	override fun onSortItemClick(item: FilterItem.Sort) {
		currentState.update { oldValue ->
			oldValue.copy(sortOrder = item.order)
		}
		repository.defaultSortOrder = item.order
	}

	override fun onTagItemClick(item: FilterItem.Tag, isFromChip: Boolean) {
		currentState.update { oldValue ->
			val newTags = if (!item.isMultiple) {
				if (isFromChip && item.isChecked) {
					emptySet()
				} else {
					setOf(item.tag)
				}
			} else if (item.isChecked) {
				oldValue.tags - item.tag
			} else {
				oldValue.tags + item.tag
			}
			oldValue.copy(tags = newTags)
		}
	}

	override fun onStateItemClick(item: FilterItem.State) {
		currentState.update { oldValue ->
			val newStates = if (item.isChecked) {
				oldValue.states - item.state
			} else {
				oldValue.states + item.state
			}
			oldValue.copy(states = newStates)
		}
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		currentState.update { oldValue ->
			oldValue.copy(
				sortOrder = oldValue.sortOrder,
				tags = if (item.payload == R.string.genres) emptySet() else oldValue.tags,
				locale = null,
				states = if (item.payload == R.string.state) emptySet() else oldValue.states,
			)
		}
	}

	fun observeAvailableTags(): Flow<Set<MangaTag>?> = flow {
		if (!availableTagsDeferred.isCompleted) {
			emit(emptySet())
		}
		emit(availableTagsDeferred.await())
	}

	fun observeState() = currentState.asStateFlow()

	fun setTags(tags: Set<MangaTag>) {
		currentState.update { oldValue ->
			oldValue.copy(tags = tags)
		}
	}

	fun reset() {
		currentState.update { oldValue ->
			oldValue.copy(oldValue.sortOrder, emptySet(), null, emptySet())
		}
	}

	fun snapshot() = currentState.value

	fun performSearch(query: String) {
		searchQuery.value = query
	}

	private fun getHeaderFlow() = combine(
		observeState(),
		observeAvailableTags(),
	) { state, available ->
		val chips = createChipsList(state, available.orEmpty(), 8)
		FilterHeaderModel(
			chips = chips,
			sortOrder = state.sortOrder,
			hasSelectedTags = state.tags.isNotEmpty(),
			allowMultipleTags = repository.isMultipleTagsSupported,
		)
	}

	private fun getItemsFlow() = combine(
		getTagsAsFlow(),
		currentState,
		searchQuery,
	) { tags, state, query ->
		buildFilterList(tags, state, query)
	}

	private fun getTagsAsFlow() = flow {
		val localTags = localTags.get()
		emit(TagsWrapper(localTags, isLoading = true, isError = false))
		val remoteTags = tryLoadTags()
		if (remoteTags == null) {
			emit(TagsWrapper(localTags, isLoading = false, isError = true))
		} else {
			emit(TagsWrapper(mergeTags(remoteTags, localTags), isLoading = false, isError = false))
		}
	}

	private suspend fun createChipsList(
		filterState: MangaListFilter.Advanced,
		availableTags: Set<MangaTag>,
		limit: Int,
	): List<ChipsView.ChipModel> {
		val selectedTags = filterState.tags.toMutableSet()
		var tags = if (selectedTags.isEmpty()) {
			searchRepository.getTagsSuggestion("", limit, repository.source)
		} else {
			searchRepository.getTagsSuggestion(selectedTags).take(limit)
		}
		if (tags.size < limit) {
			tags = tags + availableTags.take(limit - tags.size)
		}
		if (tags.isEmpty() && selectedTags.isEmpty()) {
			return emptyList()
		}
		val result = LinkedList<ChipsView.ChipModel>()
		for (tag in tags) {
			val model = ChipsView.ChipModel(
				tint = 0,
				title = tag.title,
				icon = 0,
				isCheckable = true,
				isChecked = selectedTags.remove(tag),
				data = tag,
			)
			if (model.isChecked) {
				result.addFirst(model)
			} else {
				result.addLast(model)
			}
		}
		for (tag in selectedTags) {
			val model = ChipsView.ChipModel(
				tint = 0,
				title = tag.title,
				icon = 0,
				isCheckable = true,
				isChecked = true,
				data = tag,
			)
			result.addFirst(model)
		}
		return result
	}

	@WorkerThread
	private fun buildFilterList(
		allTags: TagsWrapper,
		state: MangaListFilter.Advanced,
		query: String,
	): List<ListModel> {
		val sortOrders = repository.sortOrders.sortedByOrdinal()
		val states = repository.states
		val tags = mergeTags(state.tags, allTags.tags).toList()
		val list = ArrayList<ListModel>(tags.size + states.size + sortOrders.size + 4)
		val isMultiTag = repository.isMultipleTagsSupported
		if (query.isEmpty()) {
			if (sortOrders.isNotEmpty()) {
				list.add(ListHeader(R.string.sort_order))
				sortOrders.mapTo(list) {
					FilterItem.Sort(it, isSelected = it == state.sortOrder)
				}
			}
			if (states.isNotEmpty()) {
				list.add(
					ListHeader(
						textRes = R.string.state,
						buttonTextRes = if (state.states.isEmpty()) 0 else R.string.reset,
						payload = R.string.state,
					),
				)
				states.mapTo(list) {
					FilterItem.State(it, isChecked = it in state.states)
				}
			}
			if (allTags.isLoading || allTags.isError || tags.isNotEmpty()) {
				list.add(
					ListHeader(
						textRes = R.string.genres,
						buttonTextRes = if (state.tags.isEmpty()) 0 else R.string.reset,
						payload = R.string.genres,
					),
				)
				tags.mapTo(list) {
					FilterItem.Tag(it, isMultiple = isMultiTag, isChecked = it in state.tags)
				}
			}
			if (allTags.isError) {
				list.add(FilterItem.Error(R.string.filter_load_error))
			} else if (allTags.isLoading) {
				list.add(LoadingFooter())
			}
		} else {
			tags.mapNotNullTo(list) {
				if (it.title.contains(query, ignoreCase = true)) {
					FilterItem.Tag(it, isMultiple = isMultiTag, isChecked = it in state.tags)
				} else {
					null
				}
			}
			if (list.isEmpty()) {
				list.add(FilterItem.Error(R.string.nothing_found))
			}
		}
		return list
	}

	private suspend fun tryLoadTags(): Set<MangaTag>? {
		val shouldRetryOnError = availableTagsDeferred.isCompleted
		val result = availableTagsDeferred.await()
		if (result == null && shouldRetryOnError) {
			availableTagsDeferred = loadTagsAsync()
			return availableTagsDeferred.await()
		}
		return result
	}

	private fun loadTagsAsync() = coroutineScope.async(Dispatchers.Default, CoroutineStart.LAZY) {
		runCatchingCancellable {
			repository.getTags()
		}.onFailure { error ->
			error.printStackTraceDebug()
		}.getOrNull()
	}

	private fun mergeTags(primary: Set<MangaTag>, secondary: Set<MangaTag>): Set<MangaTag> {
		val result = TreeSet(TagTitleComparator(repository.source.locale))
		result.addAll(secondary)
		result.addAll(primary)
		return result
	}

	private data class TagsWrapper(
		val tags: Set<MangaTag>,
		val isLoading: Boolean,
		val isError: Boolean,
	)

	private class TagTitleComparator(lc: String?) : Comparator<MangaTag> {

		private val collator = lc?.let { Collator.getInstance(Locale(it)) }

		override fun compare(o1: MangaTag, o2: MangaTag): Int {
			val t1 = o1.title.lowercase()
			val t2 = o2.title.lowercase()
			return collator?.compare(t1, t2) ?: compareValues(t1, t2)
		}
	}
}
