package com.izzy2lost.nin64

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
    }

    private val prefs by lazy { getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, MODE_PRIVATE) }

    private lateinit var folderPathText: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(PREF_ROM_FOLDER_URI, uri.toString()).apply()
            updateFolderDisplay()
        installCatalogControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge()
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.topBar).applyTopBarInsets()
        findViewById<View>(R.id.settingsScroll).applyBottomContentInsets()

        folderPathText = findViewById(R.id.folderPathText)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.aboutButton).setOnClickListener {
            showAboutDialog()
        }

        findViewById<MaterialButton>(R.id.changeFolderButton).setOnClickListener {
            val current = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
            folderPicker.launch(current)
        }
        findViewById<MaterialButton>(R.id.clearUnpackedButton).setOnClickListener {
            val freed = ZippedRoms.clear(this)
            Toast.makeText(this, getString(R.string.clear_unpacked_done, freed / (1024 * 1024)), Toast.LENGTH_SHORT).show()
        }

        bindSpinner(
            spinnerId = R.id.aspectSpinner,
            labelsRes = R.array.aspect_labels,
            valuesRes = R.array.aspect_values,
            prefKey = DisplayOptionsRepository.PREF_ASPECT,
            defaultValue = DisplayOptionsRepository.DEFAULT_ASPECT
        )
        bindSpinner(
            spinnerId = R.id.resolutionSpinner,
            labelsRes = R.array.resolution_labels,
            valuesRes = R.array.resolution_values,
            prefKey = DisplayOptionsRepository.PREF_RES_FACTOR,
            defaultValue = DisplayOptionsRepository.DEFAULT_RES_FACTOR
        )

        findViewById<MaterialButton>(R.id.editTouchLayoutButton).setOnClickListener {
            TouchLayoutActivity.launch(this)
        }
        findViewById<MaterialButton>(R.id.editControllerMappingButton).setOnClickListener {
            GamepadMappingActivity.launch(this)
        }
        findViewById<MaterialButton>(R.id.resetControlsButton).setOnClickListener {
            ControlsRepository.resetGlobal(this)
            Toast.makeText(this, R.string.controls_global_reset_done, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.cheevosAccountButton).setOnClickListener {
            if (Achievements.isSignedIn(this)) confirmCheevosSignOut() else showCheevosSignIn()
        }
        findViewById<MaterialButton>(R.id.cheevosSignUpButton).setOnClickListener {
            openAboutLink(Achievements.SIGN_UP_URL)
        }
        findViewById<MaterialSwitch>(R.id.cheevosEnabledSwitch).apply {
            isChecked = Achievements.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Achievements.setEnabled(this@SettingsActivity, checked) }
        }
        findViewById<MaterialSwitch>(R.id.cheevosNotificationsSwitch).apply {
            isChecked = Achievements.notificationsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Achievements.setNotificationsEnabled(this@SettingsActivity, checked) }
        }
        updateCheevosAccountUi()


        updateFolderDisplay()
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        updateFolderDisplay()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun updateFolderDisplay() {
        val uri = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
        folderPathText.text = if (uri != null) {
            DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: getString(R.string.game_folder_not_set)
        } else {
            getString(R.string.game_folder_not_set)
        }
    }

    // ── RetroAchievements ─────────────────────────────────────────────────────

    private fun updateCheevosAccountUi() {
        val signedIn = Achievements.isSignedIn(this)
        findViewById<TextView>(R.id.cheevosAccountText).text = if (signedIn) {
            getString(R.string.settings_cheevos_signed_in, Achievements.displayName(this))
        } else {
            getString(R.string.settings_cheevos_signed_out)
        }
        findViewById<MaterialButton>(R.id.cheevosAccountButton).setText(
            if (signedIn) R.string.cheevos_sign_out else R.string.cheevos_sign_in
        )
        findViewById<View>(R.id.cheevosSignUpButton).visibility = if (signedIn) View.GONE else View.VISIBLE
    }

    private fun showCheevosSignIn() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_cheevos_login, null, false)
        val user = content.findViewById<TextView>(R.id.cheevosLoginUser)
        val password = content.findViewById<TextView>(R.id.cheevosLoginPassword)
        val status = content.findViewById<TextView>(R.id.cheevosLoginStatus)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cheevos_sign_in_title)
            .setIcon(R.drawable.ic_trophy)
            .setView(content)
            .setPositiveButton(R.string.cheevos_sign_in, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val go = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener {
                val u = user.text.toString().trim()
                val p = password.text.toString()
                if (u.isEmpty() || p.isEmpty()) return@setOnClickListener
                go.isEnabled = false
                status.setTextColor(MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant))
                status.setText(R.string.cheevos_signing_in)
                status.visibility = View.VISIBLE
                // Blocking network call: the native client signs in and hands back a token.
                thread(name = "CheevosLogin") {
                    val result = runCatching { NativeBridge.cheevosLogin(u, p) }
                        .getOrElse { arrayOf("", it.message.orEmpty()) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (result.getOrNull(0).isNullOrEmpty()) {
                            go.isEnabled = true
                            status.setTextColor(MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError))
                            status.text = getString(R.string.cheevos_sign_in_error, result.getOrNull(1).orEmpty())
                        } else {
                            Achievements.signIn(this, result[0], result[1], result.getOrNull(2).orEmpty())
                            Toast.makeText(
                                this,
                                getString(R.string.cheevos_signed_in_toast, Achievements.displayName(this)),
                                Toast.LENGTH_SHORT
                            ).show()
                            updateCheevosAccountUi()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmCheevosSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cheevos_sign_out)
            .setMessage(R.string.cheevos_sign_out_confirm)
            .setPositiveButton(R.string.cheevos_sign_out) { _, _ ->
                Achievements.signOut(this)
                updateCheevosAccountUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val labels = resources.getStringArray(R.array.about_link_labels)
        val urls = resources.getStringArray(R.array.about_link_urls)

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_info)
            .setTitle(R.string.about_nin64)
            .setItems(labels) { _, which ->
                urls.getOrNull(which)?.let(::openAboutLink)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAboutLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSpinner(
        spinnerId: Int,
        labelsRes: Int,
        valuesRes: Int,
        prefKey: String,
        defaultValue: String
    ) {
        val spinner = findViewById<Spinner>(spinnerId)
        val labels = resources.getStringArray(labelsRes)
        val values = resources.getStringArray(valuesRes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val current = prefs.getString(prefKey, defaultValue) ?: defaultValue
        val index = values.indexOf(current).let { if (it < 0) values.indexOf(defaultValue).coerceAtLeast(0) else it }
        spinner.setSelection(index, false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(prefKey, values[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }


    private fun installCatalogControls() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)

        fun findScroll(view: android.view.View): android.widget.ScrollView? {
            if (view is android.widget.ScrollView) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findScroll(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }

        val scroll = findScroll(content)
        val parent = scroll?.getChildAt(0) as? android.view.ViewGroup ?: return

        val section = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val updateButton = MaterialButton(this).apply {
            text = "Atualizar catálogo de jogos"
            setOnClickListener {
                isEnabled = false
                thread {
                    val result = runCatching { GameCatalog.update(this@SettingsActivity) }
                    runOnUiThread {
                        isEnabled = true
                        result.onSuccess {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Catálogo atualizado: $it jogos",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Falha ao atualizar catálogo: ${it.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        val addButton = MaterialButton(this).apply {
            text = "Adicionar jogo manualmente"
            setOnClickListener {
                val input = android.widget.EditText(this@SettingsActivity).apply {
                    hint = "Nome do jogo"
                    setSingleLine(true)
                }
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Novo jogo")
                    .setView(input)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Adicionar") { _, _ ->
                        val title = input.text.toString().trim()
                        if (title.isNotBlank()) {
                            runCatching {
                                GameCatalog.addManual(this@SettingsActivity, title)
                            }.onSuccess {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Jogo adicionado",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Não foi possível adicionar o jogo",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .show()
            }
        }

        section.addView(updateButton)
        section.addView(addButton)
        parent.addView(section)
    }

}
