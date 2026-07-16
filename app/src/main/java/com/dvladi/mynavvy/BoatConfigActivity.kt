package com.dvladi.mynavvy

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dvladi.mynavvy.databinding.ActivityBoatConfigBinding
import java.util.Locale

/**
 * Boat configuration. Draft and the under-keel margin are safety-critical: they decide which water
 * [WeatherRouter] will route you through, so they get their own section and are always editable
 * even after a catalogue pick.
 */
class BoatConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBoatConfigBinding
    private lateinit var profile: BoatProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoatConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profile = BoatProfile.load(this)
        bindProfile()

        binding.btnSearch.setOnClickListener { runSearch(binding.etSearch.text.toString()) }
        binding.btnSave.setOnClickListener { saveAndFinish() }
        runSearch("") // show the whole (small) catalogue up front
    }

    // The Units setting (m/ft) governs all lengths here — a US sailor reads draft in feet. Lengths are
    // stored in metres in the profile; only the display/edit fields convert.
    private val useFeet get() = profile.units == "ft"
    private val lenUnit get() = if (useFeet) "ft" else "m"
    private fun toU(m: Double) = if (useFeet) m * BoatProfile.M_TO_FT else m      // metres -> display
    private fun fromU(v: Double) = if (useFeet) v / BoatProfile.M_TO_FT else v    // display -> metres

    private fun bindProfile() {
        binding.etName.setText(profile.name)
        binding.etDraft.setText(fmt(toU(profile.draftM)))
        binding.etMargin.setText(fmt(toU(profile.ukcMarginM)))
        binding.etCruise.setText(fmt(profile.cruiseKn))            // speed stays in knots
        binding.etAirDraft.setText(fmt(toU(profile.airDraftM)))
        binding.tvDraftLabel.text = "Draft ($lenUnit)"
        binding.tvMarginLabel.text = "Under-keel clearance margin ($lenUnit)"
        binding.tvAirDraftLabel.text = "Air draft — mast height above water ($lenUnit), for bridges"
        binding.tvCurrent.text = profile.label()
        binding.tvSpecs.text = when {
            profile.loaM <= 0 -> ""
            useFeet -> String.format(Locale.US, "LOA %.1f ft · LWL %.1f ft · beam %.1f ft · disp %.0f lb",
                toU(profile.loaM), toU(profile.lwlM), toU(profile.beamM), profile.displacementKg * KG_TO_LB)
            else -> String.format(Locale.US, "LOA %.2f m · LWL %.2f m · beam %.2f m · disp %.0f kg",
                profile.loaM, profile.lwlM, profile.beamM, profile.displacementKg)
        }
    }

    private fun runSearch(q: String) {
        binding.results.removeAllViews()
        val hits = BoatCatalog.search(this, q)
        if (hits.isEmpty()) {
            addNote("No match in the catalogue — enter the numbers manually below.")
            return
        }
        hits.take(20).forEach { e ->
            val b = Button(this).apply {
                text = e.label(useFeet)
                isAllCaps = false
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { applyEntry(e) }
            }
            binding.results.addView(b)
        }
    }

    private fun addNote(msg: String) {
        binding.results.addView(android.widget.TextView(this).apply {
            text = msg
            textSize = 12f
            setTextColor(0xFF9FB3C0.toInt())
        })
    }

    private fun applyEntry(e: BoatCatalog.Entry) {
        // Keep whatever the user typed into the safety fields; take the hull specs from the catalogue.
        readEditableInto(profile)
        profile = e.applyTo(profile)
        profile.draftM = e.draftM // catalogue draft wins on an explicit pick
        bindProfile()
        Toast.makeText(this, "Applied ${e.make} ${e.model} (${e.keel} keel)", Toast.LENGTH_SHORT).show()
    }

    /** Pull the user-editable fields back into [p]; blank/garbage keeps the existing value. */
    private fun readEditableInto(p: BoatProfile) {
        p.name = binding.etName.text.toString().ifBlank { p.name }
        binding.etDraft.text.toString().toDoubleOrNull()?.let { p.draftM = fromU(it) }
        binding.etMargin.text.toString().toDoubleOrNull()?.let { p.ukcMarginM = fromU(it) }
        binding.etCruise.text.toString().toDoubleOrNull()?.let { p.cruiseKn = it }   // knots
        binding.etAirDraft.text.toString().toDoubleOrNull()?.let { p.airDraftM = fromU(it) }
    }

    private fun saveAndFinish() {
        readEditableInto(profile)
        if (profile.draftM <= 0.0) {
            Toast.makeText(this, "Draft must be greater than 0", Toast.LENGTH_LONG).show(); return
        }
        if (profile.cruiseKn <= 0.0) {
            Toast.makeText(this, "Cruise speed must be greater than 0", Toast.LENGTH_LONG).show(); return
        }
        BoatProfile.save(this, profile)
        val safety = toU(profile.draftM + profile.ukcMarginM)
        Toast.makeText(
            this,
            String.format(Locale.US, "Saved · router now avoids water under %s %s",
                if (useFeet) String.format(Locale.US, "%.1f", safety) else String.format(Locale.US, "%.2f", safety),
                lenUnit),
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun fmt(v: Double) = if (v == 0.0) "" else String.format(Locale.US, "%.2f", v)

    companion object {
        private const val KG_TO_LB = 2.2046226
    }
}
