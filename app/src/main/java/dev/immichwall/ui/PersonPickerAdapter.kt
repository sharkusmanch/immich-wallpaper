package dev.immichwall.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import dev.immichwall.R
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.api.PersonDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3-column person grid with tap-to-toggle multi-select, name filter and a
 * simple in-memory LruCache for `people/{id}/thumbnail` bitmaps (decoded on IO).
 */
class PersonPickerAdapter(
    private val client: ImmichApiClient,
    private val scope: CoroutineScope,
    private val onSelectionChanged: (count: Int) -> Unit,
) : RecyclerView.Adapter<PersonPickerAdapter.PersonViewHolder>() {

    private val all = mutableListOf<PersonDto>()
    private val visible = mutableListOf<PersonDto>()
    private val selectedIds = LinkedHashSet<String>()
    private val thumbCache = LruCache<String, Bitmap>(96)
    private var query: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submit(people: List<PersonDto>, preselectedIds: Collection<String>) {
        all.clear()
        all.addAll(people)
        selectedIds.clear()
        for (id in preselectedIds) {
            if (all.any { it.id == id }) selectedIds.add(id)
        }
        applyFilter()
        onSelectionChanged(selectedIds.size)
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    fun selectionCount(): Int = selectedIds.size

    /** Selected people in selection order. */
    fun selectedPeople(): List<PersonDto> =
        selectedIds.mapNotNull { id -> all.find { it.id == id } }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        visible.clear()
        visible.addAll(
            if (query.isBlank()) all
            else all.filter { it.name.contains(query, ignoreCase = true) }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_person, parent, false)
        return PersonViewHolder(view)
    }

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) {
        val person = visible[position]
        holder.boundId = person.id
        holder.name.text = person.name
        holder.card.isChecked = person.id in selectedIds

        val cached = thumbCache.get(person.id)
        holder.thumb.setImageBitmap(cached)
        if (cached == null) loadThumbnail(holder, person.id)

        holder.card.setOnClickListener {
            if (!selectedIds.remove(person.id)) selectedIds.add(person.id)
            holder.card.isChecked = person.id in selectedIds
            onSelectionChanged(selectedIds.size)
        }
    }

    private fun loadThumbnail(holder: PersonViewHolder, personId: String) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bytes = client.getPersonThumbnail(personId)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            thumbCache.put(personId, bitmap)
            if (holder.boundId == personId) holder.thumb.setImageBitmap(bitmap)
        }
    }

    class PersonViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val thumb: ImageView = view.findViewById(R.id.person_thumb)
        val name: TextView = view.findViewById(R.id.person_name)
        var boundId: String? = null
    }
}
