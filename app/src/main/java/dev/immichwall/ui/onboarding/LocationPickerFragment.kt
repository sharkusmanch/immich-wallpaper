package dev.immichwall.ui.onboarding

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.R
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import dev.immichwall.ui.afterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * City list from GET /api/search/cities (one representative asset per city;
 * city/country in exifInfo). Kept as a fast text list with a search filter.
 */
class LocationPickerFragment : Fragment(R.layout.fragment_location_picker) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val client = wizardApiClient()

        val search = view.findViewById<TextInputEditText>(R.id.location_search)
        val progress = view.findViewById<ProgressBar>(R.id.location_progress)
        val errorText = view.findViewById<TextView>(R.id.location_error)
        val retryButton = view.findViewById<Button>(R.id.location_retry)
        val list = view.findViewById<RecyclerView>(R.id.location_list)

        val adapter = CityAdapter { city ->
            vm.mode = WizardViewModel.Mode.LOCATION
            vm.city = city
            (requireActivity() as MainActivity).navigateTo(SourcePreviewFragment())
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        search.afterTextChanged { adapter.filter(it) }

        fun load() {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val result: Result<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
                    try {
                        val cities = client.getCities()
                            .mapNotNull { asset ->
                                val city = asset.exifInfo?.city?.takeIf { it.isNotBlank() }
                                city?.let { it to asset.exifInfo?.country }
                            }
                            .distinctBy { it.first.lowercase() }
                            .sortedBy { it.first.lowercase() }
                        Result.success(cities)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { cities ->
                        if (cities.isEmpty()) {
                            errorText.setText(R.string.location_empty)
                            errorText.visibility = View.VISIBLE
                        }
                        adapter.submit(cities)
                    },
                    onFailure = { e ->
                        errorText.text =
                            getString(R.string.location_load_error, e.message ?: e.javaClass.simpleName)
                        errorText.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                    },
                )
            }
        }
        retryButton.setOnClickListener { load() }
        load()
    }

    private class CityAdapter(
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<CityAdapter.CityViewHolder>() {

        private val all = mutableListOf<Pair<String, String?>>()
        private val visible = mutableListOf<Pair<String, String?>>()
        private var query: String = ""

        @SuppressLint("NotifyDataSetChanged")
        fun submit(cities: List<Pair<String, String?>>) {
            all.clear()
            all.addAll(cities)
            applyFilter()
        }

        fun filter(text: String) {
            query = text.trim()
            applyFilter()
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun applyFilter() {
            visible.clear()
            visible.addAll(
                if (query.isBlank()) all
                else all.filter { it.first.contains(query, ignoreCase = true) }
            )
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CityViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_two_line, parent, false)
            return CityViewHolder(view)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: CityViewHolder, position: Int) {
            val (city, country) = visible[position]
            holder.title.text = city
            if (country.isNullOrBlank()) {
                holder.subtitle.visibility = View.GONE
            } else {
                holder.subtitle.visibility = View.VISIBLE
                holder.subtitle.text = country
            }
            holder.itemView.setOnClickListener { onClick(city) }
        }

        class CityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.item_title)
            val subtitle: TextView = view.findViewById(R.id.item_subtitle)
        }
    }
}
