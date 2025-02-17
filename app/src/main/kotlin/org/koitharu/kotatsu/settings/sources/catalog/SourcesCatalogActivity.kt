package org.koitharu.kotatsu.settings.sources.catalog

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import coil.ImageLoader
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.firstVisibleItemPosition
import org.koitharu.kotatsu.core.util.ext.getLocaleDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType
import javax.inject.Inject

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	TabLayout.OnTabSelectedListener,
	OnListItemClickListener<SourceCatalogItem.Source>,
	AppBarOwner, MenuItem.OnActionExpandListener {

	@Inject
	lateinit var coil: ImageLoader

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<SourcesCatalogViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		initTabs()
		val sourcesAdapter = SourcesCatalogAdapter(this, coil, this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			adapter = sourcesAdapter
		}
		viewModel.content.observe(this, sourcesAdapter)
		viewModel.onActionDone.observeEvent(
			this,
			ReversibleActionObserver(viewBinding.recyclerView),
		)
		viewModel.locale.observe(this) {
			supportActionBar?.subtitle = it.getLocaleDisplayName(this)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this))
	}

	override fun onWindowInsetsChanged(insets: Insets) {
		viewBinding.root.updatePadding(
			left = insets.left,
			right = insets.right,
		)
		viewBinding.recyclerView.updatePadding(
			bottom = insets.bottom + viewBinding.recyclerView.paddingTop,
		)
	}

	override fun onItemClick(item: SourceCatalogItem.Source, view: View) {
		viewModel.addSource(item.source)
	}

	override fun onTabSelected(tab: TabLayout.Tab) {
		viewModel.setContentType(tab.tag as ContentType)
	}

	override fun onTabUnselected(tab: TabLayout.Tab) = Unit

	override fun onTabReselected(tab: TabLayout.Tab) {
		viewBinding.recyclerView.firstVisibleItemPosition = 0
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		viewBinding.tabs.isVisible = false
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		viewBinding.tabs.isVisible = true
		viewModel.performSearch(null)
		return true
	}

	private fun initTabs() {
		val tabs = viewBinding.tabs
		for (type in ContentType.entries) {
			if (viewModel.isNsfwDisabled && type == ContentType.HENTAI) {
				continue
			}
			val tab = tabs.newTab()
			tab.setText(type.titleResId)
			tab.tag = type
			tabs.addTab(tab)
		}
		tabs.addOnTabSelectedListener(this)
	}
}
