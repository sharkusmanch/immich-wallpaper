package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import dev.immichwall.R
import dev.immichwall.ui.MainActivity

/** First wizard screen: intro + privacy note. */
class WelcomeFragment : Fragment(R.layout.fragment_welcome) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Button>(R.id.welcome_start).setOnClickListener {
            (requireActivity() as MainActivity).navigateTo(ServerSetupFragment())
        }
    }
}
