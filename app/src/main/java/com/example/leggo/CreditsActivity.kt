package com.example.leggo

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button

class CreditsActivity : BaseActivity() { // Eredita da BaseActivity

    private lateinit var btnConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credits)

        btnConfirm = findViewById(R.id.btnConfirmCopyright)

        val prefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val hasConfirmed = prefs.getBoolean("copyright_accepted", false)

        if (hasConfirmed) {
            btnConfirm.visibility = View.GONE
        } else {
            btnConfirm.visibility = View.VISIBLE
        }

        btnConfirm.setOnClickListener {
            prefs.edit().putBoolean("copyright_accepted", true).apply()
            it.visibility = View.GONE
        }
    }
}