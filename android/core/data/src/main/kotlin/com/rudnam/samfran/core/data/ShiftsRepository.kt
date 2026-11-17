package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.ReadProgress
import com.rudnam.samfran.core.model.ShiftCoworker
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftKind
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.model.ShiftSettings
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.Server
import com.rudnam.samfran.core.network.model.NetworkCoworker
import com.rudnam.samfran.core.network.model.NetworkNameFix
import com.rudnam.samfran.core.network.model.NetworkSaveShifts
import com.rudnam.samfran.core.network.model.NetworkShiftCode
import com.rudnam.samfran.core.network.model.NetworkShiftMonth
import com.rudnam.samfran.core.network.model.NetworkShiftSettings
import com.rudnam.samfran.core.network.model.NetworkShiftSettingsInput
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Sam's shifts: a month at a time, the photo reader, the settings and the calendar feed. */
interface ShiftsRepository {
    /** A month as last fetched; null [month] is the current one (the server picks it, in the shifts' time zone). */
    fun month(month: YearMonth?): Flow<ShiftMonth?>

    suspend fun refresh(month: YearMonth?): Outcome<ShiftMonth>

    /** The next shift that hasn't ended, looking into next month if this one has none left. */
    suspend fun nextShift(now: Instant): ShiftDay?

    /** When working shifts end from now on (last, this and next month's), for "Start bus alarm?". */
    suspend fun upcomingEnds(now: Instant): Outcome<List<Instant>>

    /**
     * Saves a month's codes (empty ones are left out), with who's on the
     * same shift for the days whose code is as it was read or saved.
     * [corrections] are names the photo reader got wrong (as read to as
     * fixed), so later scans get them right.
     */
    suspend fun save(
        month: YearMonth,
        codes: Map<LocalDate, String>,
        with: Map<LocalDate, List<String>> = emptyMap(),
        corrections: Map<String, String> = emptyMap(),
        /** Others at work around each day, as read or saved; the server keeps those who overlap. */
        others: Map<LocalDate, List<ShiftCoworker>> = emptyMap(),
    ): Outcome<ShiftMonth>

    /** Reads a photo of the sheet, telling [onProgress] how far it's got; nothing is saved. The answer waits in [pendingRead] for the editor. */
    suspend fun readPhoto(jpeg: ByteArray, onProgress: (ReadProgress) -> Unit = {}): Outcome<ShiftMonth>

    /** The photo reader's answer, taken once by the editor. */
    fun takePendingRead(month: YearMonth): ShiftMonth?

    val settings: Flow<ShiftSettings?>

    suspend fun refreshSettings(): Outcome<ShiftSettings>

    suspend fun saveSettings(personName: String, nameOnSheet: String, lengths: Map<String, Double>, closingDays: List<Int>? = null): Outcome<ShiftSettings>

    /** Turns the calendar link on (a new address each time) or off. */
    suspend fun feed(on: Boolean): Outcome<ShiftSettings>

    /** A path on the server (the calendar link's), as a full address. */
    fun address(path: String): String
}

@Singleton
internal class OfflineFirstShiftsRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    private val server: Server,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : ShiftsRepository {
    private val serializer = NetworkShiftMonth.serializer()

    @Volatile private var pending: ShiftMonth? = null

    private fun key(month: YearMonth?) = Keys.shifts(month?.toString().orEmpty())

    override fun month(month: YearMonth?): Flow<ShiftMonth?> = documents.observe(key(month), serializer) { it.asModel() }

    override suspend fun refresh(month: YearMonth?) = outcome(io) {
        val fresh = network.shifts(month?.toString())
        keep(fresh, current = month == null)
        fresh.asModel()
    }

    /** The current month is kept under its own name too, so moving to it by month shows it at once. */
    private suspend fun keep(m: NetworkShiftMonth, current: Boolean = false) {
        documents.put(Keys.shifts(m.month), serializer, m)
        if (current) documents.put(Keys.shifts(""), serializer, m)
        documents.put(Keys.SHIFT_SETTINGS, NetworkShiftSettings.serializer(), m.settings)
    }

    override suspend fun nextShift(now: Instant): ShiftDay? {
        val current = refresh(null).valueOrNull ?: documents.get(key(null), serializer)?.asModel() ?: return null
        fun upcoming(m: ShiftMonth) = m.days.firstOrNull { d -> d.end?.toInstant()?.isAfter(now) == true }
        upcoming(current)?.let { return it }
        val next = refresh(current.month.plusMonths(1)).valueOrNull ?: return null
        return upcoming(next)
    }

    override suspend fun upcomingEnds(now: Instant) = outcome(io) {
        val current = network.shifts(null).also { keep(it, current = true) }.asModel()
        val around = listOf(current.month.minusMonths(1), current.month.plusMonths(1)).map { network.shifts(it.toString()).also { m -> keep(m) }.asModel() }
        (around + current).flatMap { it.days }
            .filter { it.kind == ShiftKind.Work }
            .mapNotNull { it.end?.toInstant() }
            .filter { it.isAfter(now) }
            .sorted()
    }

    override suspend fun save(
        month: YearMonth,
        codes: Map<LocalDate, String>,
        with: Map<LocalDate, List<String>>,
        corrections: Map<String, String>,
        others: Map<LocalDate, List<ShiftCoworker>>,
    ) = outcome(io) {
        val days = codes.entries.sortedBy { it.key }.mapNotNull { (date, code) ->
            code.takeIf { it.isNotBlank() }?.let {
                NetworkShiftCode(
                    date.toString(),
                    it,
                    with[date]?.takeIf(List<String>::isNotEmpty),
                    others[date]?.takeIf(List<ShiftCoworker>::isNotEmpty)?.map { o -> NetworkCoworker(o.name, o.code, o.dayOffset) },
                )
            }
        }
        val fixes = corrections.map { (from, to) -> NetworkNameFix(from, to) }.takeIf { it.isNotEmpty() }
        val saved = network.saveShifts(NetworkSaveShifts(month.toString(), days, fixes))
        keep(saved)
        // The current month's copy, if this is it.
        documents.get(key(null), serializer)?.takeIf { it.month == saved.month }?.let { documents.put(key(null), serializer, saved) }
        saved.asModel()
    }

    override suspend fun readPhoto(jpeg: ByteArray, onProgress: (ReadProgress) -> Unit) = outcome(io) {
        onProgress(ReadProgress.Sending)
        network.readPhoto(jpeg) { p -> onProgress(ReadProgress.Reading(p.nameAsRead, p.daysRead, p.daysInMonth)) }.asModel()
    }.also {
        pending = (it as? Outcome.Done)?.value
    }

    override fun takePendingRead(month: YearMonth): ShiftMonth? = pending?.takeIf { it.month == month }.also { pending = null }

    override val settings: Flow<ShiftSettings?> = documents.observe(Keys.SHIFT_SETTINGS, NetworkShiftSettings.serializer()) { it.asModel() }

    override suspend fun refreshSettings() = settingsChange { network.shiftSettings() }

    override suspend fun saveSettings(personName: String, nameOnSheet: String, lengths: Map<String, Double>, closingDays: List<Int>?) = settingsChange {
        network.saveShiftSettings(NetworkShiftSettingsInput(personName.trim(), nameOnSheet.trim(), lengths, closingDays))
    }

    override suspend fun feed(on: Boolean) = settingsChange { network.feed(on) }

    private suspend fun settingsChange(block: suspend () -> NetworkShiftSettings) = outcome(io) {
        block().also { documents.put(Keys.SHIFT_SETTINGS, NetworkShiftSettings.serializer(), it) }.asModel()
    }

    override fun address(path: String) = server.resolve(path)
}
