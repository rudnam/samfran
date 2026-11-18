package com.rudnam.samfran.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/**
 * What the phone does while the app is closed (the app's ActivityJob):
 * telling one of you when the other changes something. Off until switched
 * on, on this phone, and needs notifications: the screen asks for that
 * permission before switching it on.
 */
interface Reminders {
    val activityAlerts: Flow<Boolean>

    /** Answers whether it's on now (off when notifications aren't allowed). */
    suspend fun setActivityAlerts(on: Boolean): Boolean

    /**
     * Brings the phone up to date with what's switched on: what's been seen
     * here. [now]: even if it was done a few minutes ago.
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
