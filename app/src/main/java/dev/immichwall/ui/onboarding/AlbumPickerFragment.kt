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
import dev.immichwall.R
import dev.immichwall.api.AlbumDto
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Album list (name + asset count); tap selects and moves on to the preview. */
class AlbumPickerFragment : Fragment(R.layout.fragment_album_picker) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val client = wizardApiClient()

        val progress = view.findViewById<ProgressBar>(R.id.album_progress)
        val errorText = view.findViewById<TextView>(R.id.album_error)
        val retryButton = view.findViewById<Button>(R.id.album_retry)
        val list = view.findViewById<RecyclerView>(R.id.album_list)

        val adapter = AlbumAdapter { album ->
            vm.mode = WizardViewModel.Mode.ALBUM
            vm.albumId = album.id
            vm.albumName = album.albumName
            (requireActivity() as MainActivity).navigateTo(SourcePreviewFragment())
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        fun load() {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val result: Result<List<AlbumDto>> = withContext(Dispatchers.IO) {
                    try {
                        Result.success(client.getAlbums().sortedBy { it.albumName.lowercase() })
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { albums ->
                        if (albums.isEmpty()) {
                            errorText.setText(R.string.album_empty)
                            errorText.visibility = View.VISIBLE
                        }
                        adapter.submit(albums)
                    },
                    onFailure = { e ->
                        errorText.text =
                            getString(R.string.album_load_error, e.message ?: e.javaClass.simpleName)
                        errorText.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                    },
                )
            }
        }
        retryButton.setOnClickListener { load() }
        load()
    }

    private class AlbumAdapter(
        private val onClick: (AlbumDto) -> Unit,
    ) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

        private val items = mutableListOf<AlbumDto>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(albums: List<AlbumDto>) {
            items.clear()
            items.addAll(albums)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_two_line, parent, false)
            return AlbumViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
            val album = items[position]
            holder.title.text = album.albumName
            holder.subtitle.text =
                holder.itemView.context.getString(R.string.album_count, album.assetCount)
            holder.subtitle.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onClick(album) }
        }

        class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.item_title)
            val subtitle: TextView = view.findViewById(R.id.item_subtitle)
        }
    }
}
