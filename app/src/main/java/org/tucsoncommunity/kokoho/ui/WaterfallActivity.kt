package org.tucsoncommunity.kokoho.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.tucsoncommunity.kokoho.databinding.ActivityWaterfallBinding
import org.tucsoncommunity.kokoho.sdr.AudioDemod
import org.tucsoncommunity.kokoho.sdr.FreqNames
import org.tucsoncommunity.kokoho.sdr.RadioWatch
import org.tucsoncommunity.kokoho.sdr.SdrRadio
import org.tucsoncommunity.kokoho.sdr.SdrWaterfall
import org.tucsoncommunity.kokoho.util.HwLogs
import org.tucsoncommunity.kokoho.util.applySystemBarInsets
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SDR's live radio waterfall. Opens on the band sweep (one row per sweep of the watched bands);
 * tapping a frequency or Tune zooms in (~2 MHz live view + optional live audio demodulation), and
 * detection resumes immediately on Back to sweep or closing the screen. Receive-only.
 */
class WaterfallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaterfallBinding
    private val queue = ConcurrentLinkedQueue<SdrWaterfall.Row>()
    private val posted = AtomicBoolean(false)
    private val drain = Runnable {
        posted.set(false)
        var any = false
        while (true) { val row = queue.poll() ?: break; binding.waterfall.add(row); any = true }
        if (any) binding.waterfall.invalidate()
    }
    /** An antenna check is waiting for its answer. */
    private var checking = false
    /** Rows show the zoomed-in view (vs the sweep), to clear queued rows when switching. */
    private var showingTuned: Boolean? = null

    private val onRow: (SdrWaterfall.Row) -> Unit = { row ->
        queue.add(row)
        while (queue.size > MAX_QUEUED) queue.poll()
        if (posted.compareAndSet(false, true)) binding.waterfall.post(drain)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaterfallBinding.inflate(layoutInflater)
        val bg = org.tucsoncommunity.kokoho.posture.KokohoPostureEngine.currentPostureColorArgb(this)
        val header = KokohoJourneyUi.wrapActivityWithSubjourneyHeader(
            activity = this,
            contentView = binding.root,
            title = "Radio Waterfall",
            bgColorArgb = bg,
            menuItemsProvider = {
                listOf(
                    KokohoJourneyUi.SubjourneyMenuItem("Tune / Listen…") { askFrequency() },
                    KokohoJourneyUi.SubjourneyMenuItem("Run Antenna Check") { antennaCheck() }
                )
            }
        )
        val shell = header.root.parent as View
        setContentView(shell)
        shell.applySystemBarInsets()
        binding.waterfall.onTap = { hz -> zoom(hz) }
        binding.tuneButton.setOnClickListener { askFrequency() }
        binding.sweepButton.setOnClickListener { SdrRadio.listen = false; SdrRadio.tuneHz = null; refresh() }
        binding.antennaButton.setOnClickListener { antennaCheck() }
        if (savedInstanceState == null) intent.getLongExtra(EXTRA_TUNE_HZ, 0L).takeIf { it > 0 }?.let { zoom(it) }
        refreshWhileStarted(1000) { refresh() }
    }

    override fun onStart() {
        super.onStart()
        SdrWaterfall.listener = onRow
        SdrRadio.screenOpened()
        HwLogs.sdr.log("Radio waterfall screen opened")
    }

    override fun onStop() {
        super.onStop()
        SdrWaterfall.listener = null
        HwLogs.sdr.log("Radio waterfall screen closed")
        queue.clear()
        if (!isChangingConfigurations) {
            SdrRadio.screenClosed()
            SdrRadio.listen = false
            SdrRadio.tuneHz = null
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun zoom(hz: Long) {
        if (!SdrRadio.running) return
        SdrRadio.tuneHz = (hz + RadioWatch.STEP_HZ / 2) / RadioWatch.STEP_HZ * RadioWatch.STEP_HZ
        refresh()
    }

    /** Quick test of FM broadcast + NOAA weather radio channels to verify antenna and dongle health. */
    private fun antennaCheck() {
        if (!SdrRadio.running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        checking = true
        refresh()
        SdrRadio.antennaCheck = { r ->
            runOnUiThread {
                checking = false
                if (!isDestroyed) refresh()
                if (!isFinishing && !isDestroyed) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Antenna check")
                        .setMessage(r.verdict + "\n\nMeasured on FM radio (87.5–108 MHz) and NOAA weather radio (162.400–162.550 MHz).")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun askFrequency() {
        if (!SdrRadio.running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        val listenItem = SdrRadio.tuneHz?.let { hz ->
            if (SdrRadio.listen) "Stop listening"
            else String.format(Locale.US, "Listen to %.4f MHz\n%s, at the phone's media volume", hz / 1e6,
                AudioDemod.modeFor(hz).label.replaceFirstChar { it.uppercase() })
        }
        val items = listOfNotNull(listenItem) + PRESETS.map { "${it.name}\n${it.about}" } + "Type a frequency…"
        MaterialAlertDialogBuilder(this)
            .setTitle("What to look at")
            .setItems(items.toTypedArray()) { _, picked ->
                if (listenItem != null && picked == 0) {
                    SdrRadio.listen = !SdrRadio.listen
                    refresh()
                    return@setItems
                }
                val i = if (listenItem != null) picked - 1 else picked
                PRESETS.getOrNull(i)?.let { zoom(it.hz) } ?: typeFrequency()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun typeFrequency() {
        val input = EditText(this).apply {
            hint = "Frequency in MHz, e.g. 155.475"
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            SdrRadio.tuneHz?.let { setText(String.format(Locale.US, "%.4f", it / 1e6)) }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tune to")
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(input) })
            .setPositiveButton("Tune") { _, _ ->
                val mhz = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (mhz == null || mhz < MIN_MHZ || mhz > MAX_MHZ) {
                    Toast.makeText(this, "Enter a frequency from $MIN_MHZ to $MAX_MHZ MHz", Toast.LENGTH_SHORT).show()
                } else zoom((mhz * 1e6).toLong())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        val tuned = SdrRadio.tuneHz
        if (showingTuned != (tuned != null)) {
            showingTuned = tuned != null
            queue.clear()
        }
        val running = SdrRadio.running
        binding.waterfall.focusHz = tuned
        binding.statusText.text = when {
            !running -> SdrRadio.status.ifBlank { "No SDR running" }
            tuned != null -> String.format(Locale.US, "%.4f MHz", tuned / 1e6) +
                ((FreqNames.lookup(tuned, RadioWatch.STEP_HZ)?.name ?: PRESETS.firstOrNull { it.hz == tuned }?.name)?.let { " · $it" } ?: "") +
                "\n" + SdrRadio.status
            else -> SdrRadio.status
        }
        binding.hintText.text = when {
            !running -> "Plug in an RTL-SDR or HackRF and start scanning to see the radio bands live."
            tuned != null -> (PRESETS.firstOrNull { it.hz == tuned }?.look ?: "Zoomed in on about 2 MHz.") +
                " Pinch to zoom; tap or use arrow keys to move; Tune › Listen plays audio; Back to sweep resumes scanning."
            SdrRadio.p25Hold != null -> "The SDR is holding a P25 talkgroup. Tune to look at a frequency."
            else -> "One row per sweep of your watched bands, newest on top. Tap or pinch to zoom in, tap again at 8× to tune, or tap Tune."
        }
        binding.sweepButton.isEnabled = running && tuned != null
        binding.tuneButton.isEnabled = running
        binding.antennaButton.isEnabled = running && !checking
        binding.antennaButton.text = if (checking) "Checking…" else "Antenna check"
        androidx.core.view.ViewCompat.setStateDescription(binding.waterfall, when {
            !running -> "No SDR running"
            tuned != null -> String.format(Locale.US, "Zoomed in on %.3f MHz", tuned / 1e6)
            else -> "Sweep of your watched bands"
        })
    }

    private class Preset(val name: String, val about: String, val hz: Long, val look: String)

    companion object {
        private val PRESETS = listOf(
            Preset("FM radio", "Broadcast stations, always on: a quick first test", 98_000_000L,
                "FM broadcast stations show as wide bright stripes."),
            Preset("Weather radio (NOAA)", "162.400 to 162.550 MHz", 162_475_000L,
                "A nearby NOAA transmitter shows as a steady continuous stripe."),
            Preset("Federal VHF", "162 to 174 MHz tactical/repeater band", 168_500_000L,
                "P25 and analog VHF channels show as sharp intermittent columns."),
            Preset("Federal UHF", "406.1 to 420 MHz federal band", 412_000_000L,
                "Federal UHF repeaters and simplex radios show as crisp narrow bursts."),
            Preset("700 / 800 MHz P25", "Trunked control & voice channels", 856_000_000L,
                "Control channels run continuously; voice grants appear beside them."),
            Preset("Aircraft voice", "Pilots and towers, 118 to 120 MHz", 119_000_000L,
                "AM airband transmissions show as short bright marks."),
            Preset("Aircraft transponders", "ADS-B, 1090 MHz", 1_090_000_000L,
                "Mode S / ADS-B squitters appear as short speckles around 1090 MHz.")
        )
        const val EXTRA_TUNE_HZ = "tune_hz"
        private const val MAX_QUEUED = 64
        private const val MIN_MHZ = 1.0
        private const val MAX_MHZ = 6000.0
    }
}
