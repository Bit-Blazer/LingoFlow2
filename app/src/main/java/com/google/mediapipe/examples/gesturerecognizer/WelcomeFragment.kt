package com.google.mediapipe.examples.gesturerecognizer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation

class WelcomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_welcome, container, false)

        view.findViewById<View>(R.id.button_auto_read).setOnClickListener {
            Navigation.findNavController(view)
                .navigate(R.id.action_welcome_to_auto_read)
        }        

        // Point to Read Mode (Navigates to Camera)
        view.findViewById<View>(R.id.button_point_to_read).setOnClickListener {
            Navigation.findNavController(view)
                .navigate(R.id.action_welcome_to_permission)
        }

        return view
    }
}
