package com.rudnam.samfran.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/**
 * What the phone does while the app is closed (the app's ShiftPrompt and
 * ActivityJob): asking "Start bus alarm?" when a shift ends, and telling one
 * of you when the other changes something. Both are off until switched on,
 * on this phone, and need notifications: the screen asks for that permission
 * before switching one on.
 */
interface Reminders {
    val shiftPrompts: Flow<Boolean>

    val activityAlerts: Flow<Boolean>

    /** Answers whether it's on now (off when notifications aren't allowed). */
    suspend fun setShiftPrompts(on: Boolean): Boolean

    suspend fun setActivityAlerts(on: Boolean): Boolean

    /**
     * Brings the phone up to date with what's switched on: the shift ends as
     * they are now, and what's been seen here. [now]: even if it was done a
     * few minutes ago (shifts or the stop just changed).
     */
    suspend fun sync(now: Boolean = false)

    /** The app is open: what its notifications were asking or telling has been seen. */
    fun clearNotifications()

    /** Signing out: nothing more for this person. */
    fun turnOff()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds fun auth(impl: ServerAuthRepository): AuthRepository
    @Binds fun user(impl: ServerUserRepository): UserRepository
    @Binds fun updates(impl: ServerUpdateRepository): UpdateRepository
    @Binds fun food(impl: OfflineFirstFoodRepository): FoodRepository
    @Binds fun shifts(impl: OfflineFirstShiftsRepository): ShiftsRepository
    @Binds fun bus(impl: OfflineFirstBusRepository): BusRepository
    @Binds fun space(impl: OfflineFirstSpaceRepository): SpaceRepository
    @Binds fun activity(impl: OfflineFirstActivityRepository): ActivityRepository
}
